package dev.veyra.downloads

import java.security.MessageDigest

internal actual class FileSha256 actual constructor() {
    private val digest = MessageDigest.getInstance("SHA-256")
    actual fun update(bytes: ByteArray, offset: Int, count: Int) = digest.update(bytes, offset, count)
    actual fun hex(): String = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    actual fun close() = Unit
}
