package tv.p2160.core.subtitle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.i18n.I18n

/**
 * Один раз на папку спрашивает дерево SAF, чтобы прочитать субтитры рядом с `content://`.
 * Уже выданное дерево и отказ запоминаются: повторный запуск того же ролика диалог не открывает.
 * Без [attach] (плеер без Activity) запрос пропускается.
 */
object SubtitleFolders {

    class Contract : ActivityResultContract<Intent, Uri?>() {
        override fun createIntent(context: Context, input: Intent): Intent = input

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    private var launch: ((Intent) -> Unit)? = null
    private var pending: CompletableDeferred<Uri?>? = null

    fun attach(launch: (Intent) -> Unit) {
        this.launch = launch
    }

    /** Снимает запуск, только если это всё ещё тот же экземпляр. Поворот экрана не затирает новую Activity. */
    fun detach(launch: (Intent) -> Unit) {
        if (this.launch === launch) this.launch = null
    }

    fun deliver(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }

    /**
     * Ждёт, пока пользователь укажет папку. `null` - отказались, папку уже спрашивали
     * или показать выбор некому. Выданное дерево сохраняется.
     */
    suspend fun request(context: Context, videoUri: Uri): Uri? {
        val key = ContentSidecars.folderKey(videoUri) ?: return null
        if (SubtitleFolderMemory.wasAsked(context, key)) return null
        val launch = launch ?: return null
        pending?.let { return it.await() }
        val prompt = I18n.get(context).current["player.subs_folder"]
        val intent = ContentSidecars.treeIntent(videoUri, prompt)
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        try {
            withContext(Dispatchers.Main.immediate) { launch(intent) }
            val granted = deferred.await()
            if (granted == null) {
                SubtitleFolderMemory.markAsked(context, key)
            } else {
                SubtitleSupport.persistReadPermission(context, granted)
                val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(granted) }.getOrNull()
                val parent = ContentSidecars.documentId(videoUri)?.let(ContentSidecars::parentDocumentId)
                val covers = treeId != null && parent != null && ContentSidecars.treeCovers(treeId, parent)
                if (!covers) SubtitleFolderMemory.markAsked(context, key)
            }
            return granted
        } finally {
            if (pending === deferred) pending = null
        }
    }
}
