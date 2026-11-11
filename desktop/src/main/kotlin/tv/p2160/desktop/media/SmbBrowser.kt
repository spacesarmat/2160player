package tv.p2160.desktop.media

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.security.bc.BCSecurityProvider
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
)

/**
 * Обзор SMB 2/3 через smbj, как в Android-модуле, но без Android Context.
 * Пароль живёт только в памяти сессии и в локальном файле библиотеки, если сервер сохранён.
 */
class SmbBrowser : AutoCloseable {
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30_000)
            .withReadBufferSize(1024 * 1024)
            .withMultiProtocolNegotiate(true)
            .build(),
    )

    fun listShares(server: SmbSaved): List<String> {
        val session = connect(server)
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).shares1
            .filter { it.type and 0xFFFF == 0 }
            .map { it.netName }
            .filter { !it.endsWith("$") }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun list(server: SmbSaved, share: String, path: String): List<SmbEntry> {
        val disk = openShare(server, share)
        return disk.list(path.replace('/', '\\'))
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith('.') }
            .filter { it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value == 0L }
            .map { info ->
                val directory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                val child = SmbPaths.child(path, info.fileName)
                SmbEntry(info.fileName, child, directory, if (directory) 0L else info.endOfFile)
            }
            .sortedWith(compareBy<SmbEntry> { !it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
    }

    fun openRead(server: SmbSaved, share: String, path: String, size: Long): SmbFileSource {
        val disk = openShare(server, share)
        val file = disk.openFile(
            path.replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return SmbFileSource(file, size)
    }

    private fun openShare(server: SmbSaved, share: String): DiskShare {
        val session = connect(server)
        return session.connectShare(share) as? DiskShare
            ?: throw IllegalStateException("\\\\${server.host}\\$share не дисковая папка")
    }

    private fun connect(server: SmbSaved) = client.connect(server.host).authenticate(auth(server))

    private fun auth(server: SmbSaved): AuthenticationContext = when {
        server.user.isBlank() -> AuthenticationContext.guest()
        else -> AuthenticationContext(server.user, server.password.toCharArray(), server.domain.ifBlank { null })
    }

    override fun close() {
        runCatching { client.close() }
    }
}

class SmbFileSource(
    private val file: com.hierynomus.smbj.share.File,
    override val length: Long,
) : ByteSource {
    private val lock = Any()

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int = synchronized(lock) {
        if (offset >= length) return -1
        val n = file.read(dst, offset, dstOff, len)
        if (n < 0) -1 else n
    }

    override fun close() {
        runCatching { file.close() }
    }
}
