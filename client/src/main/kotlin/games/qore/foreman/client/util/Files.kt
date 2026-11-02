package games.qore.foreman.client.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")

/** Writes a file only this user can read (it holds a token), created that way so it is never readable by others. */
fun writePrivateFile(file: Path, content: String): Path {
    Files.createDirectories(file.parent)
    if (Files.notExists(file)) runCatching { Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY)) }
    Files.writeString(file, content)
    runCatching { Files.setPosixFilePermissions(file, OWNER_ONLY) }
    return file
}

/** A random secret, in hex. */
fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
