package tv.p2160.core.source.smb

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.security.bc.BCSecurityProvider
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** Элемент списка в папке SMB. */
data class SmbEntry(
    val path: SmbPath,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
) {
    val name: String get() = path.name
}

/**
 * Пул SMB-подключений (SMB 2/3 через smbj). Одно соединение на хост, одна сессия на учётку,
 * share кэшируются; при обрыве переподключаемся прозрачно.
 */
object SmbConnections {

    private val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                // В Android нет MD4 для NTLM — берём реализацию из BouncyCastle.
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30_000)
                .withReadBufferSize(4 * 1024 * 1024)
                .withMultiProtocolNegotiate(true)
                .build()
        )
    }

    private data class Key(val host: String, val share: String, val user: String)

    private val shares = HashMap<Key, DiskShare>()
    private val sessions = HashMap<Key, Session>()

    /** Открытая share с учётными данными из [SmbServers] (или явными [credentials]). */
    @Synchronized
    fun share(context: Context, host: String, share: String, credentials: SmbServer? = null): DiskShare {
        val cred = credentials ?: SmbServers.get(context).credentialsFor(host, share)
        val key = Key(host.lowercase(), share.lowercase(), cred?.username.orEmpty())
        shares[key]?.takeIf { it.isConnected }?.let { return it }

        val session = sessions[key]?.takeIf { it.connection.isConnected } ?: run {
            val connection: Connection = client.connect(host)
            val auth = when {
                cred == null || cred.username.isBlank() -> AuthenticationContext.guest()
                else -> AuthenticationContext(cred.username, cred.password.toCharArray(), cred.domain.ifBlank { null })
            }
            connection.authenticate(auth).also { sessions[key] = it }
        }
        val disk = session.connectShare(share) as? DiskShare
            ?: throw IllegalStateException("\\\\$host\\$share is not a disk share")
        shares[key] = disk
        return disk
    }

    fun share(context: Context, path: SmbPath, credentials: SmbServer? = null): DiskShare =
        share(context, path.host, path.share, credentials)

    fun openRead(context: Context, path: SmbPath): File =
        share(context, path).openFile(
            path.smbjPath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )

    /** Содержимое папки: сначала папки, потом файлы, по алфавиту; скрытые и служебные пропускаем. */
    fun list(context: Context, dir: SmbPath, credentials: SmbServer? = null): List<SmbEntry> =
        share(context, dir, credentials).list(dir.smbjPath)
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith('.') }
            .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value == 0L }
            .map {
                SmbEntry(
                    path = dir.child(it.fileName),
                    isDirectory = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareBy<SmbEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()

    /** Проверка подключения (для диалога «Добавить сервер»). */
    fun test(context: Context, server: SmbServer) {
        list(context, SmbPath(server.host, server.share, server.path), server)
    }

    @Synchronized
    fun closeAll() {
        shares.values.forEach { runCatching { it.close() } }
        sessions.values.forEach { runCatching { it.close() } }
        shares.clear()
        sessions.clear()
    }
}
