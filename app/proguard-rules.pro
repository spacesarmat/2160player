# FFmpeg JNI в release: поля буфера декодера читает libmedia3ext.so по имени.
-keep class androidx.media3.decoder.** { *; }
-keep class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.** { *; }
-keepclassmembers class androidx.media3.decoder.VideoDecoderOutputBuffer {
    java.nio.ByteBuffer data;
    long decoderPrivate;
}

# SMB (smbj + dcerpc): шина событий mbassador и ASN.1 работают через рефлексию.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn com.rapid7.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
