/*
 * Superset of nextlib's FfmpegLibrary (Apache 2.0, itself based on Media3's ffmpeg extension).
 * nextlib 1.11.1-0.16.0 already links mpeg4, msmpeg4v2, msmpeg4v3 and h263 inside libavcodec,
 * but getCodecName() does not map those MIME types, so "FFmpeg mode" never tries them.
 * This class is compiled into player-core. The nextlib jar on the classpath has the original
 * FfmpegLibrary removed, so there is one copy in the AAR and in the APK. Native methods stay the same.
 */
package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

@OptIn(markerClass = UnstableApi.class)
public final class FfmpegLibrary {
    private static final String TAG = "FfmpegLibrary";
    private static final LibraryLoader LOADER;
    private static String version;
    private static int inputBufferPaddingSize;

    static {
        MediaLibraryInfo.registerModule("media3.decoder.ffmpeg");
        LOADER = new NativeLoader("media3ext");
        inputBufferPaddingSize = -1;
    }

    private FfmpegLibrary() {}

    public static void setLibraries(String... libraries) {
        LOADER.setLibraries(libraries);
    }

    public static boolean isAvailable() {
        return LOADER.isAvailable();
    }

    public static String getVersion() {
        if (!isAvailable()) {
            return null;
        }
        if (version == null) {
            version = ffmpegGetVersion();
        }
        return version;
    }

    public static int getInputBufferPaddingSize() {
        if (!isAvailable()) {
            return -1;
        }
        if (inputBufferPaddingSize == -1) {
            inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
        }
        return inputBufferPaddingSize;
    }

    public static boolean supportsFormat(String mimeType) {
        if (!isAvailable()) {
            return false;
        }
        String codecName = getCodecName(mimeType);
        if (codecName == null) {
            return false;
        }
        if (!ffmpegHasDecoder(codecName)) {
            Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
            return false;
        }
        return true;
    }

    static String getCodecName(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        switch (mimeType) {
            case "audio/mp4a-latm":
                return "aac";
            case "audio/mpeg":
            case "audio/mpeg-L1":
            case "audio/mpeg-L2":
                return "mp3";
            case "audio/ac3":
                return "ac3";
            case "audio/eac3":
            case "audio/eac3-joc":
                return "eac3";
            case "audio/true-hd":
                return "truehd";
            case "audio/vnd.dts":
            case "audio/vnd.dts.hd":
                return "dca";
            case "audio/vorbis":
                return "vorbis";
            case "audio/opus":
                return "opus";
            case "audio/3gpp":
                return "amrnb";
            case "audio/amr-wb":
                return "amrwb";
            case "audio/flac":
                return "flac";
            case "audio/alac":
                return "alac";
            case "audio/g711-mlaw":
                return "pcm_mulaw";
            case "audio/g711-alaw":
                return "pcm_alaw";
            case "video/avc":
                return "h264";
            case "video/hevc":
                return "hevc";
            case "video/av01":
                return "libdav1d";
            case "video/mpeg":
                return "mpegvideo";
            case "video/mpeg2":
                return "mpeg2video";
            case "video/x-vnd.on2.vp8":
                return "vp8";
            case "video/x-vnd.on2.vp9":
                return "vp9";
            case "video/mp4v-es":
                return "mpeg4";
            case "video/mp42":
                return "msmpeg4v2";
            case "video/mp43":
                return "msmpeg4v3";
            case "video/3gpp":
                return "h263";
            default:
                return null;
        }
    }

    private static native String ffmpegGetVersion();

    private static native int ffmpegGetInputBufferPaddingSize();

    private static native boolean ffmpegHasDecoder(String codecName);

    private static final class NativeLoader extends LibraryLoader {
        NativeLoader(String... libraries) {
            super(libraries);
        }

        @Override
        protected void loadLibrary(String name) {
            System.loadLibrary(name);
        }
    }
}
