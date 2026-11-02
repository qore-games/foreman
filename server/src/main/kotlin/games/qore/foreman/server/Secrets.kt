package games.qore.foreman.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Tokens, and the files that hold them: readable by the server's user only. */
object Secrets {
    private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
    private val random = SecureRandom()

    /** A new token: [prefix] then 32 random bytes in base64url. */
    fun token(prefix: String): String =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** Writes [content] to [file], created readable by this user only where the system has POSIX permissions. */
    fun write(file: File, content: String) {
        val path = file.toPath()
        Files.createDirectories(path.parent)
        if (Files.notExists(path)) runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
        Files.writeString(path, content)
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
    }

    /** `mcp.token` when set, else the token in [file], made at the first start. */
    fun mcpToken(configured: String, file: File): String {
        if (configured.isNotBlank()) return configured.trim()
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return token("fm_").also { write(file, it + "\n") }
    }

    /** Compares two secrets in a time that doesn't depend on where they differ. */
    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.encodeToByteArray(), b.encodeToByteArray())
}
