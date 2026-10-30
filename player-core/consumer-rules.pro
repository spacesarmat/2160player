# FFmpeg JNI bindings from nextlib
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class tv.p2160.core.api.** { *; }

# nextlib FFmpeg (libmedia3ext.so, ffmpegInitialize) ищет поля по имени через JNI.
# R8 в release их выкидывает: SIGABRT, VideoDecoderOutputBuffer.data / decoderPrivate.
-keep class androidx.media3.decoder.** { *; }
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.** { *; }
-keepclassmembers class androidx.media3.decoder.VideoDecoderOutputBuffer {
    java.nio.ByteBuffer data;
    long decoderPrivate;
}
