// Мост 2160 Player ↔ NDI® SDK: библиотека libndi.so загружается динамически (dlopen), как рекомендует
// документация NDI SDK для открытых проектов. NDI® is a registered trademark of Vizrt NDI AB (https://ndi.video/).
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include "Processing.NDI.Lib.h"

#define TAG "NdiBridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static const NDIlib_v6* ndi = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_tv_p2160_app_camera_Ndi_nativeLoad(JNIEnv* env, jclass, jstring configDir) {
    if (ndi) return JNI_TRUE;
    // Настройки NDI (имя машины) читаются из NDI_CONFIG_DIR/ndi-config.v1.json при инициализации.
    if (configDir) {
        const char* dir = env->GetStringUTFChars(configDir, nullptr);
        setenv("NDI_CONFIG_DIR", dir, 1);
        env->ReleaseStringUTFChars(configDir, dir);
    }
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
    if (!api || !api->initialize()) {
        LOGW("NDI initialize failed (unsupported CPU?)");
        return JNI_FALSE;
    }
    ndi = api;
    return JNI_TRUE;
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

extern "C" JNIEXPORT void JNICALL
Java_tv_p2160_app_camera_Ndi_nativeSendVideo(JNIEnv* env, jclass, jlong handle, jobject buffer,
                                             jint width, jint height, jint stride, jint fpsN, jint fpsD) {
    if (!ndi || !handle) return;
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) return;
    NDIlib_video_frame_v2_t frame{};
    frame.xres = width;
    frame.yres = height;
    frame.FourCC = NDIlib_FourCC_video_type_RGBX;
    frame.frame_rate_N = fpsN;
    frame.frame_rate_D = fpsD;
    frame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.p_data = data;
    frame.line_stride_in_bytes = stride;
    // Синхронно: NDI сжимает кадр здесь же, буфер ImageReader нужен только до возврата.
    ndi->send_send_video_v2(reinterpret_cast<NDIlib_send_instance_t>(handle), &frame);
}

extern "C" JNIEXPORT void JNICALL
Java_tv_p2160_app_camera_Ndi_nativeSendAudio(JNIEnv* env, jclass, jlong handle, jbyteArray pcm,
                                             jint length, jint sampleRate, jint channels) {
    if (!ndi || !handle || length <= 0) return;
    jbyte* bytes = env->GetByteArrayElements(pcm, nullptr);
    NDIlib_audio_frame_interleaved_16s_t frame{};
    frame.sample_rate = sampleRate;
    frame.no_channels = channels;
    frame.no_samples = length / (2 * channels);
    frame.timecode = NDIlib_send_timecode_synthesize;
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
