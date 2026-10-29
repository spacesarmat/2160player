package tv.p2160.core.subtitle

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Субтитры рядом с видео, открытым как `content://`.
 *
 * Сначала путь на диске (`primary:Download/Movie.mp4` и колонка MediaStore), если каталог читается.
 * Иначе уже выданное дерево SAF, которое накрывает папку. Если ни того ни другого нет,
 * [Search.askForFolder] говорит плееру спросить папку один раз.
 */
internal object ContentSidecars {

    data class Hit(val uri: Uri, val name: String)

    data class Search(val hits: List<Hit>, val askForFolder: Boolean)

    /** Корень основного тома. В тестах подменяется на временный каталог. */
    internal var primaryRoot: (Context) -> File = { Environment.getExternalStorageDirectory() }

    fun parentDocumentId(documentId: String): String? {
        val slash = documentId.lastIndexOf('/')
        if (slash <= 0) return null
        return documentId.substring(0, slash)
    }

    fun documentId(uri: Uri): String? {
        if (!uri.scheme.equals("content", true)) return null
        val fromContract = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (!fromContract.isNullOrEmpty()) return fromContract
        val path = uri.path ?: return null
        val marker = "/document/"
        val at = path.indexOf(marker)
        if (at < 0) return null
        val raw = path.substring(at + marker.length)
        return Uri.decode(raw).takeIf { it.isNotEmpty() }
    }

    /** `authority|parentId`, либо сам URI, если родителя из идентификатора не видно (`msf:100`). */
    fun folderKey(uri: Uri): String? {
        if (!uri.scheme.equals("content", true)) return null
        val id = documentId(uri) ?: return null
        val parent = parentDocumentId(id)
        return if (parent != null) "${uri.authority.orEmpty()}|$parent" else "uri:$uri"
    }

    fun treeCovers(treeDocumentId: String, documentId: String): Boolean {
        if (treeDocumentId.isEmpty()) return false
        if (documentId == treeDocumentId) return true
        if (documentId.startsWith("$treeDocumentId/")) return true
        // Корень тома - `primary:`, файлы внутри - `primary:Download/Movie.mp4`, без слэша после двоеточия.
        return treeDocumentId.endsWith(':') &&
            documentId.startsWith(treeDocumentId) &&
            documentId.length > treeDocumentId.length
    }

    /**
     * Файл по идентификатору внешнего накопителя: `primary:Download/Movie.mp4`.
     * `null` - это не путь тома (`msf:100`, `home:`).
     */
    fun storageFile(documentId: String, primaryRoot: File, volumeRoot: (String) -> File? = { File("/storage/$it") }): File? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1)
        if (relative.isEmpty()) return null
        val root = when (volume) {
            "primary" -> primaryRoot
            "home" -> return null
            else -> volumeRoot(volume) ?: return null
        }
        return File(root, relative)
    }

    fun namesMatching(base: String, names: Iterable<String>): List<String> =
        names.filter { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            ext in SubtitleSupport.EXTENSIONS && SubtitleSupport.matchesStem(base, name)
        }.sorted()

    fun search(context: Context, videoUri: Uri): Search {
        if (!videoUri.scheme.equals("content", true)) return Search(emptyList(), false)
        val base = baseName(context, videoUri) ?: return Search(emptyList(), false)
        listedOnDisk(context, videoUri)?.let { return Search(it, false) }
        listedInGrantedTrees(context, videoUri, base)?.let { return Search(it, false) }
        val key = folderKey(videoUri) ?: return Search(emptyList(), false)
        return Search(emptyList(), !SubtitleFolderMemory.wasAsked(context, key))
    }

    fun listTree(context: Context, videoUri: Uri, treeUri: Uri): List<Hit> {
        val base = baseName(context, videoUri) ?: return emptyList()
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        val parent = parentDocumentId(documentId(videoUri).orEmpty())
        val directoryId = when {
            parent != null && treeCovers(treeId, parent) -> parent
            parent == null -> treeId
            else -> return emptyList()
        }
        return queryChildren(context, treeUri, directoryId, base) ?: emptyList()
    }

    fun treeIntent(videoUri: Uri, prompt: String): android.content.Intent {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (prompt.isNotBlank()) intent.putExtra(android.content.Intent.EXTRA_TITLE, prompt)
        if (Build.VERSION.SDK_INT >= 26) {
            if (prompt.isNotBlank()) intent.putExtra(DocumentsContract.EXTRA_PROMPT, prompt)
            initialDocumentUri(videoUri)?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        return intent
    }

    fun initialDocumentUri(videoUri: Uri): Uri? {
        val id = documentId(videoUri) ?: return null
        val parent = parentDocumentId(id) ?: return null
        val authority = videoUri.authority ?: return null
        return runCatching { DocumentsContract.buildDocumentUri(authority, parent) }.getOrNull()
    }

    fun baseName(context: Context, videoUri: Uri): String? {
        val id = documentId(videoUri)
        val fromId = id?.substringAfterLast('/')
        if (fromId != null && '.' in fromId && !fromId.startsWith(".")) {
            return fromId.substringBeforeLast('.')
        }
        val display = SubtitleSupport.displayName(context, videoUri) ?: return null
        val dot = display.lastIndexOf('.')
        if (dot <= 0) return null
        return display.substring(0, dot)
    }

    private fun listedOnDisk(context: Context, videoUri: Uri): List<Hit>? {
        val id = documentId(videoUri)
        val file = id?.let { storageFile(it, primaryRoot(context)) { volumeRoot(context, it) } }
            ?: mediaStoreFile(context, videoUri)
            ?: return null
        val files = SubtitleSupport.listNextTo(file) ?: return null
        return files.map { Hit(Uri.fromFile(it), it.name) }
    }

    private fun listedInGrantedTrees(context: Context, videoUri: Uri, base: String): List<Hit>? {
        val id = documentId(videoUri) ?: return null
        val parent = parentDocumentId(id) ?: return null
        var saw = false
        var found: List<Hit> = emptyList()
        for (tree in context.contentResolver.persistedUriPermissions) {
            if (!tree.isReadPermission) continue
            val treeUri = tree.uri
            if (!DocumentsContract.isTreeUri(treeUri)) continue
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (!treeCovers(treeId, parent)) continue
            val children = queryChildren(context, treeUri, parent, base) ?: continue
            saw = true
            found = children
            if (children.isNotEmpty()) return children
        }
        return if (saw) found else null
    }

    private fun queryChildren(context: Context, treeUri: Uri, directoryId: String, base: String): List<Hit>? {
        val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId) }.getOrNull()
            ?: return null
        return runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (idCol < 0 || nameCol < 0) return@use emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val name = cursor.getString(nameCol) ?: continue
                        if (namesMatching(base, listOf(name)).isEmpty()) continue
                        val docId = cursor.getString(idCol) ?: continue
                        val child = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }.getOrNull() ?: continue
                        add(Hit(child, name))
                    }
                }.sortedBy { it.name }
            }
        }.getOrNull()
    }

    private fun mediaStoreFile(context: Context, uri: Uri): File? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val path = cursor.getString(0) ?: return@use null
            File(path).takeIf { it.parentFile != null }
        }
    }.getOrNull()

    private fun volumeRoot(context: Context, volume: String): File? {
        if (Build.VERSION.SDK_INT >= 24) {
            val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
            manager?.storageVolumes?.forEach { storage ->
                if (storage.uuid.equals(volume, true) && Build.VERSION.SDK_INT >= 30) {
                    storage.directory?.let { return it }
                }
            }
        }
        return File("/storage/$volume").takeIf { it.isDirectory }
    }
}

/** Папки, для которых выбор дерева уже показывали и пользователь закрыл его или указал другую папку. */
internal object SubtitleFolderMemory {
    private const val PREFS = "p2160_subtitle_folders"

    fun wasAsked(context: Context, key: String): Boolean =
        prefs(context).getBoolean(key, false)

    fun markAsked(context: Context, key: String) {
        prefs(context).edit().putBoolean(key, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
