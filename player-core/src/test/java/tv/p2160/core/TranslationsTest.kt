package tv.p2160.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Проверяет встроенные языковые пакеты всех модулей: одинаковый набор ключей
 * и одинаковые плейсхолдеры (%1$s, %2$d…) — иначе перевод упадёт на форматировании.
 */
class TranslationsTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private val packDirs = listOf("player-core", "app")
        .map { File(root, "$it/src/main/assets/i18n") }
        .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }

    private val entry = Regex("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val placeholder = Regex("%\\d+\\$[-+.0-9]*[sdf]")

    private fun load(file: File): Map<String, String> =
        entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
            .filterKeys { it !in setOf("code", "name", "author", "base") }

    @Test
    fun packsExist() {
        assertTrue("no i18n directories found", packDirs.isNotEmpty())
        packDirs.forEach { dir ->
            assertTrue("$dir/en.json missing", File(dir, "en.json").exists())
            assertTrue("$dir/ru.json missing", File(dir, "ru.json").exists())
        }
    }

    @Test
    fun allLanguagesHaveSameKeysAndPlaceholders() {
        packDirs.forEach { dir ->
            val en = load(File(dir, "en.json"))
            dir.listFiles { f -> f.extension == "json" }!!.forEach { file ->
                val other = load(file)
                assertEquals("keys differ in ${file.path}", en.keys.sorted(), other.keys.sorted())
                en.forEach { (key, value) ->
                    assertEquals(
                        "placeholders differ for \"$key\" in ${file.path}",
                        placeholder.findAll(value).map { it.value }.sorted().toList(),
                        placeholder.findAll(other.getValue(key)).map { it.value }.sorted().toList(),
                    )
                }
            }
        }
    }

    @Test
    fun keysDoNotCollideBetweenModules() {
        val seen = mutableMapOf<String, String>()
        packDirs.forEach { dir ->
            load(File(dir, "en.json")).keys.forEach { key ->
                val previous = seen.put(key, dir.path)
                assertTrue("key \"$key\" defined in both $previous and ${dir.path}", previous == null)
            }
        }
    }
}
