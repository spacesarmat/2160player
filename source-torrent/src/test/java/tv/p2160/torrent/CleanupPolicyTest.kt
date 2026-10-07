package tv.p2160.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupPolicyTest {
    private val gb = 1024L * 1024 * 1024
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_000 * day

    private fun item(id: String, idle: Long, bytes: Long, active: Boolean = false) =
        CleanupPolicy.Item(id, now - idle, bytes, active)

    @Test
    fun oldTorrentsRemovedAndWatchedDataDropped() {
        val plan = CleanupPolicy(maxAgeDays = 7, sizeLimitBytes = 0, keepFiles = false).plan(
            listOf(
                item("old", 8 * day, 5 * gb),
                item("yesterday", 30 * hour, 2 * gb),
                item("fresh", 2 * hour, 2 * gb),
                item("noData", 3 * day, 0),
            ),
            now,
        )
        assertEquals(mapOf("old" to CleanupPolicy.Action.REMOVE, "yesterday" to CleanupPolicy.Action.DELETE_DATA), plan)
    }

    @Test
    fun keepFilesDisablesAgeRulesButNotSizeLimit() {
        val policy = CleanupPolicy(maxAgeDays = 7, sizeLimitBytes = 10 * gb, keepFiles = true)
        val plan = policy.plan(
            listOf(item("a", 100 * day, 6 * gb), item("b", 50 * day, 6 * gb), item("c", 1 * day, 3 * gb)),
            now,
        )
        // 15 ГБ > 10: удаляем данные самого давнего ("a"), остаётся 9 ГБ
        assertEquals(mapOf("a" to CleanupPolicy.Action.DELETE_DATA), plan)
    }

    @Test
    fun activeTorrentsAreNeverTouched() {
        val plan = CleanupPolicy(maxAgeDays = 1, sizeLimitBytes = gb, keepFiles = false).plan(
            listOf(item("playing", 30 * day, 50 * gb, active = true), item("other", 1 * hour, 2 * gb)),
            now,
        )
        assertEquals(mapOf("other" to CleanupPolicy.Action.DELETE_DATA), plan)
    }

    @Test
    fun noLimitMeansNoSizeEviction() {
        val plan = CleanupPolicy(maxAgeDays = 0, sizeLimitBytes = 0, keepFiles = true)
            .plan(listOf(item("a", 1000 * day, 500 * gb)), now)
        assertTrue(plan.isEmpty())
    }

    @Test
    fun sizeLimitCountsAfterAgeRemovals() {
        val plan = CleanupPolicy(maxAgeDays = 7, sizeLimitBytes = 5 * gb, keepFiles = false, dataGraceHours = 1000)
            .plan(listOf(item("old", 10 * day, 10 * gb), item("mid", 2 * day, 3 * gb), item("new", 1 * day, 1 * gb)), now)
        // "old" удаляется по возрасту; оставшиеся 4 ГБ укладываются в лимит
        assertEquals(mapOf("old" to CleanupPolicy.Action.REMOVE), plan)
    }
}
