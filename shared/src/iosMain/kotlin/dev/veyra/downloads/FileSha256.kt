@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.downloads

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update

/** CommonCrypto streaming SHA-256; far faster than a portable Kotlin/Native implementation. */
internal actual class FileSha256 actual constructor() {
    private var context: CC_SHA256_CTX? = nativeHeap.alloc<CC_SHA256_CTX>().also { CC_SHA256_Init(it.ptr) }

    actual fun update(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        if (count == 0) return
        val active = checkNotNull(context) { "The hash was already finished." }
        bytes.usePinned { CC_SHA256_Update(active.ptr, it.addressOf(offset), count.convert()) }
    }

    actual fun hex(): String {
        val active = checkNotNull(context) { "The hash was already finished." }
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { CC_SHA256_Final(it.addressOf(0), active.ptr) }
        close()
        return digest.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    actual fun close() {
        context?.let { nativeHeap.free(it) }
        context = null
    }
}
