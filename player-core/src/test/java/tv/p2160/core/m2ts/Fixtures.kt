package tv.p2160.core.m2ts

import java.io.File
import java.util.concurrent.TimeUnit

/** Пути к тестовым данным. Всё, чего нет на машине, тесты пропускают через Assume. */
object Fixtures {

    // Реальные файлы задаются переменными окружения; без них тесты пропускаются.
    val fallen = File(System.getenv("P2160_FALLEN_M2TS") ?: "")

    val aquamanIso = File(System.getenv("P2160_AQUAMAN_ISO") ?: System.getenv("BLURAY_TEST_ISO") ?: "")

    private val ffmpeg: File? = System.getenv("P2160_FFMPEG")?.let(::File)?.takeIf { it.isFile }

    private val workDir = File(System.getProperty("java.io.tmpdir"), "p2160-m2ts-tests").apply { mkdirs() }

    /**
     * Синтетический M2TS (12 с) от ffmpeg в режиме m2ts:
     * PID 0x1100 — LPCM 3.0 24 бит (0x80, нечётные каналы → заполнитель),
     * 0x1101 — TrueHD 5.1 (0x83), 0x1102 — DTS 5.1 (0x82), 0x1103 — E-AC-3 моно (0x84).
     */
    val synthetic: File? by lazy {
        val out = File(workDir, "synth-bd-audio.m2ts")
        if (out.isFile && out.length() > 0) return@lazy out
        val ff = ffmpeg ?: return@lazy null
        val cmd = listOf(
            ff.path, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "aevalsrc=sin(2*PI*440*t)|sin(2*PI*550*t)|sin(2*PI*660*t):s=48000:d=12:c=3.0",
            "-f", "lavfi", "-i",
            "aevalsrc=0.5*sin(2*PI*300*t)|0.5*sin(2*PI*400*t)|0.5*sin(2*PI*500*t)|0.1*sin(2*PI*60*t)|" +
                "0.5*sin(2*PI*700*t)|0.5*sin(2*PI*800*t):s=48000:d=12:c=5.1",
            "-f", "lavfi", "-i", "sine=f=1000:r=48000:d=12",
            "-map", "0", "-map", "1", "-map", "1", "-map", "2",
            "-c:a:0", "pcm_bluray", "-sample_fmt:a:0", "s32",
            "-c:a:1", "truehd", "-c:a:2", "dca", "-c:a:3", "eac3", "-strict", "-2",
            "-f", "mpegts", "-mpegts_m2ts_mode", "1", out.path,
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0 || !out.isFile) null else out
    }
}
