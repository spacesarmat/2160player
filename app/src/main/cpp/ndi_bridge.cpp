// Мост 2160 Player ↔ NDI® SDK: библиотека libndi.so загружается динамически (dlopen), как рекомендует
// документация NDI SDK для открытых проектов. NDI® is a registered trademark of Vizrt NDI AB (https://ndi.video/).
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include "Processing.NDI.Lib.h"

#define TAG "NdiBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const NDIlib_v6* ndi = nullptr;
static bool initialized = false;

// Один буфер на отправку: send_send_video_v2 синхронный и возвращается после своей копии.
// Память ImageReader (gralloc) NDI читает чёрной, пока её не прочитали с CPU, поэтому кадр копируется сюда.
static std::mutex videoPoolMu;
static uint8_t* videoPool = nullptr;
static size_t videoPoolCap = 0;

// dstLen может быть больше srcLen: у ImageReader последняя строка короче rowStride, а NDI читает stride*height.
static uint8_t* stageVideoFrame(const uint8_t* src, size_t srcLen, size_t dstLen) {
    if (!src || dstLen == 0 || srcLen > dstLen) return nullptr;
    if (videoPoolCap < dstLen) {
        auto* next = static_cast<uint8_t*>(realloc(videoPool, dstLen));
        if (!next) return nullptr;
        videoPool = next;
        videoPoolCap = dstLen;
    }
    memcpy(videoPool, src, srcLen);
    if (dstLen > srcLen) memset(videoPool + srcLen, 0, dstLen - srcLen);
    return videoPool;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_tv_p2160_app_camera_Ndi_nativeLoad(JNIEnv*, jclass) {
    if (ndi) return JNI_TRUE;
    // В APK лежит libndi.so; системный загрузчик найдёт её в каталоге библиотек приложения.
    void* lib = dlopen("libndi.so", RTLD_LOCAL | RTLD_NOW);
    if (!lib) {
        LOGW("dlopen libndi.so: %s", dlerror());
        return JNI_FALSE;
    }
    auto load = reinterpret_cast<const NDIlib_v6* (*)(void)>(dlsym(lib, "NDIlib_v6_load"));
    if (!load) {
        LOGW("NDIlib_v6_load not found");
        return JNI_FALSE;
    }
    const NDIlib_v6* api = load();
    if (!api || !api->is_supported_CPU()) {
        LOGW("NDI: unsupported CPU");
        return JNI_FALSE;
    }
    ndi = api;
    return JNI_TRUE;
}

// (Пере)инициализация перед трансляцией: настройки (имя машины, разрешённые сетевые адаптеры) читаются из
// NDI_CONFIG_DIR/ndi-config.v1.json только при initialize, а адреса Wi-Fi между трансляциями могут смениться.
extern "C" JNIEXPORT jboolean JNICALL
Java_tv_p2160_app_camera_Ndi_nativeInit(JNIEnv* env, jclass, jstring configDir) {
    if (!ndi) return JNI_FALSE;
    if (initialized) {
        ndi->destroy();
        initialized = false;
    }
    if (configDir) {
        const char* dir = env->GetStringUTFChars(configDir, nullptr);
        setenv("NDI_CONFIG_DIR", dir, 1);
        env->ReleaseStringUTFChars(configDir, dir);
    }
    initialized = ndi->initialize();
    if (!initialized) LOGW("NDI initialize failed");
    return initialized ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_tv_p2160_app_camera_Ndi_nativeCreateSender(JNIEnv* env, jclass, jstring jname) {
    if (!ndi) return 0;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    NDIlib_send_create_t create{};
    create.p_ndi_name = name;
    create.p_groups = nullptr;
    // Видео и звук отправляем в своём темпе (кадры приходят с камеры/экрана в реальном времени).
    create.clock_video = false;
    create.clock_audio = false;
    NDIlib_send_instance_t sender = ndi->send_create(&create);
    env->ReleaseStringUTFChars(jname, name);
    return reinterpret_cast<jlong>(sender);
}

// RGBX, не NV12: GL отдаёт RGBA, перевод в YUV на CPU добавил бы проход по кадру перед собственным SpeedHQ у NDI.
extern "C" JNIEXPORT void JNICALL
Java_tv_p2160_app_camera_Ndi_nativeSendVideo(JNIEnv* env, jclass, jlong handle, jobject buffer,
                                             jint width, jint height, jint stride, jint fpsN, jint fpsD,
                                             jlong timecode) {
    if (!ndi || !handle || width <= 0 || height <= 0) return;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!base) return;
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    jclass bufClass = env->GetObjectClass(buffer);
    jmethodID posM = env->GetMethodID(bufClass, "position", "()I");
    jmethodID limM = env->GetMethodID(bufClass, "limit", "()I");
    env->DeleteLocalRef(bufClass);
    if (!posM || !limM || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    const jint pos = env->CallIntMethod(buffer, posM);
    const jint lim = env->CallIntMethod(buffer, limM);
    if (env->ExceptionCheck() || pos < 0 || lim < pos || static_cast<jlong>(lim) > capacity) {
        env->ExceptionClear();
        return;
    }
    const size_t n = static_cast<size_t>(lim - pos);
    if (stride <= 0) return;
    const size_t strideU = static_cast<size_t>(stride);
    const size_t heightU = static_cast<size_t>(height);
    if (heightU > SIZE_MAX / strideU) return;
    const size_t need = strideU * heightU;
    if (n == 0 || need == 0) return;
    std::lock_guard<std::mutex> lock(videoPoolMu);
    const size_t srcLen = n < need ? n : need;
    uint8_t* data = stageVideoFrame(base + pos, srcLen, need);
    if (!data) return;
    NDIlib_video_frame_v2_t frame{};
    frame.xres = width;
    frame.yres = height;
    frame.FourCC = NDIlib_FourCC_video_type_RGBX;
    frame.frame_rate_N = fpsN;
    frame.frame_rate_D = fpsD;
    frame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    // Единицы NDI — 100 нс. Long.MAX_VALUE совпадает с NDIlib_send_timecode_synthesize.
    frame.timecode = static_cast<int64_t>(timecode);
    frame.p_data = data;
    frame.line_stride_in_bytes = stride;
    ndi->send_send_video_v2(reinterpret_cast<NDIlib_send_instance_t>(handle), &frame);
}

extern "C" JNIEXPORT void JNICALL
Java_tv_p2160_app_camera_Ndi_nativeSendAudio(JNIEnv* env, jclass, jlong handle, jbyteArray pcm,
                                             jint length, jint sampleRate, jint channels, jlong timecode) {
    if (!ndi || !handle || length <= 0) return;
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    if (!bytes) return;
    NDIlib_audio_frame_interleaved_16s_t frame{};
    frame.sample_rate = sampleRate;
    frame.no_channels = channels;
    frame.no_samples = length / (2 * channels);
    frame.timecode = static_cast<int64_t>(timecode);
    frame.reference_level = 0;
    frame.p_data = reinterpret_cast<int16_t*>(bytes);
    ndi->util_send_send_audio_interleaved_16s(reinterpret_cast<NDIlib_send_instance_t>(handle), &frame);
    env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT jint JNICALL
Java_tv_p2160_app_camera_Ndi_nativeConnections(JNIEnv*, jclass, jlong handle) {
    if (!ndi || !handle) return 0;
    return ndi->send_get_no_connections(reinterpret_cast<NDIlib_send_instance_t>(handle), 0);
}

extern "C" JNIEXPORT void JNICALL
Java_tv_p2160_app_camera_Ndi_nativeDestroySender(JNIEnv*, jclass, jlong handle) {
    if (!ndi || !handle) return;
    ndi->send_destroy(reinterpret_cast<NDIlib_send_instance_t>(handle));
}
