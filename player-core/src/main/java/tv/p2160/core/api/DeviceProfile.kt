package tv.p2160.core.api

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration

/**
 * Возможности устройства для подбора буферов и лимитов: сколько памяти, большая ли куча, ТВ ли это.
 * Используется плеером ([PlayerConfig.AUTO]) и модулем торрентов (лимиты соединений).
 */
object DeviceProfile {
    /** «Слабое» устройство: система помечает его как low-RAM или памяти меньше 2 ГБ. */
    fun lowMemory(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.isLowRamDevice || totalMemoryMb(context) in 1 until 2048
    }

    /** Вся оперативная память устройства, МБ (0 — неизвестно). */
    fun totalMemoryMb(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        return ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024 * 1024)
    }

    /** Предел кучи Java для этого приложения, МБ (с учётом `android:largeHeap`). */
    fun heapLimitMb(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 128
        val large = context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP != 0
        return if (large) am.largeMemoryClass else am.memoryClass
    }

    fun isTv(context: Context): Boolean =
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /**
     * Предел буфера плеера в байтах для [PlayerConfig.AUTO]: четверть кучи (буфер Media3 живёт в ней), но не
     * больше 1/24 всей памяти устройства; от 24 до 128 МБ, на low-RAM — не больше 32 МБ.
     * Примеры: телефон с 8 ГБ и largeHeap — 128 МБ; ТВ-приставка с 1,5 ГБ — ~32–64 МБ.
     */
    fun bufferTargetBytes(context: Context): Int {
        val heapPart = heapLimitMb(context) / 4
        val total = totalMemoryMb(context)
        val ramPart = if (total > 0) (total / 24).toInt() else heapPart
        var mb = minOf(heapPart, ramPart).coerceIn(24, 128)
        if (lowMemory(context)) mb = minOf(mb, 32)
        return mb * 1024 * 1024
    }
}
