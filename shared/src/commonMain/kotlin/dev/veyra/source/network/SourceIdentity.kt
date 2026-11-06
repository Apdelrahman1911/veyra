package dev.veyra.source.network

/** Stable SHA-256 IDs, never filename/path fragments derived from untrusted titles or GUIDs. */
internal fun sourceDigest(value: String): String {
    val input = value.encodeToByteArray()
    val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    val bits = input.size.toLong() * 8
    for (i in 0..7) padded[padded.lastIndex - i] = (bits ushr (i * 8)).toByte()
    val h = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19).map { it.toInt() }.toIntArray()
    val w = IntArray(64)
    for (offset in padded.indices step 64) {
        for (i in 0..15) {
            var word = 0
            for (j in 0..3) word = (word shl 8) or (padded[offset + i * 4 + j].toInt() and 255)
            w[i] = word
        }
        for (i in 16..63) {
            val a = w[i - 15]; val b = w[i - 2]
            w[i] = w[i - 16] + (a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)) +
                w[i - 7] + (b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10))
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var z = h[7]
        for (i in 0..63) {
            val t1 = z + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) +
                ((e and f) xor (e.inv() and g)) + SHA256_K[i] + w[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) +
                ((a and b) xor (a and c) xor (b and c))
            z = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        val state = intArrayOf(a, b, c, d, e, f, g, z)
        for (i in h.indices) h[i] += state[i]
    }
    return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
}

private val SHA256_K = longArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
).map { it.toInt() }.toIntArray()
