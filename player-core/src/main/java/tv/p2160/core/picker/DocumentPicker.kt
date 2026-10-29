package tv.p2160.core.picker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * «Загрузить из файла…». На Android 9 TV нет обработчика `ACTION_OPEN_DOCUMENT`,
 * и `startActivity` падает с [ActivityNotFoundException].
 * Сначала системный выбор, затем `ACTION_GET_CONTENT`, затем обзор приложения, если его зарегистрировали.
 */
object DocumentPicker {

    /** Обзор самого приложения: локальные файлы и SMB. `audio` отличает звук от субтитров. */
    private var appPicker: ((Context, Boolean) -> Intent)? = null

    fun setAppPicker(factory: (Context, Boolean) -> Intent) {
        appPicker = factory
    }

    fun canPick(context: Context): Boolean =
        appPicker != null || resolves(context, openDocument()) || resolves(context, getContent())

    /**
     * Запускает первый подходящий выбор. [launch] бросает [ActivityNotFoundException], если обработчика нет.
     * [onMissing] - ни один вариант не открылся.
     */
    fun open(context: Context, audio: Boolean, launch: (Intent) -> Unit, onMissing: () -> Unit) {
        val options = ArrayList<Intent>(3)
        options += openDocument()
        options += getContent()
        appPicker?.invoke(context, audio)?.let { options += it }
        for (intent in options) {
            try {
                launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                continue
            }
        }
        onMissing()
    }

    private fun openDocument(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")

    private fun getContent(): Intent =
        Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")

    private fun resolves(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
}
