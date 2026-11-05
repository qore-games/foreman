package games.qore.foreman.client.util

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

/** Writes a file only this user can read (it holds a token), created that way so it is never readable by others. */
fun writePrivateFile(file: Path, content: String): Path {
    Files.createDirectories(file.parent)
    // Whole or not at all: a reader (a launcher polling client-mcp.json) never sees half of it. A temporary file is
    // the user's only from the start.
    val tmp = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp")
    try {
        Files.writeString(tmp, content)
        runCatching { Files.setPosixFilePermissions(tmp, OWNER_ONLY) }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
    return file
}

/** A random secret, in hex. */
fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
