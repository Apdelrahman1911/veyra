package dev.veyra.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 180-2 known answers; chunked updates must equal one-shot hashing. */
class FileSha256Test {
    private fun hash(vararg parts: ByteArray) = FileSha256().run { parts.forEach { update(it, 0, it.size) }; hex() }

    @Test fun knownAnswersAndChunkingAgree() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash("abc".encodeToByteArray()))
        val long = ByteArray(1_000_003) { (it % 251).toByte() }
        assertEquals(hash(long), hash(long.copyOfRange(0, 65_536), long.copyOfRange(65_536, long.size)))
        val hasher = FileSha256(); hasher.update(byteArrayOf(9, 1, 2, 3), 1, 3)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", hasher.hex())
    }
}
