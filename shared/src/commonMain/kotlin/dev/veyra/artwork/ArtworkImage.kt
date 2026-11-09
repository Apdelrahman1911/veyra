package dev.veyra.artwork

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException

/** Bounded image headers, not a replacement for the UI/native image decoder. No SVG or animation. */
internal data class ArtworkImage(val mimeType: String, val extension: String, val width: Int, val height: Int) {
    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        const val PREFIX_BYTES = 256L * 1024
        const val ACCEPT = "image/jpeg, image/png, image/webp"

        fun requireMime(value: String?): String = value?.substringBefore(';')?.trim()?.lowercase()
            ?.takeIf { it in setOf("image/jpeg", "image/png", "image/webp") }
            ?: artworkFailure(FailureCode.UNSUPPORTED_SOURCE, "artwork_mime_not_supported")

        fun inspect(prefix: ByteArray, tail: ByteArray, size: Long, declaredMime: String): ArtworkImage {
            if (size !in 1..MAX_BYTES) artworkFailure(FailureCode.PARSE_FAILURE, "artwork_size_limit")
            val mime = requireMime(declaredMime)
            fun invalid(): Nothing = artworkFailure(FailureCode.PARSE_FAILURE, "artwork_invalid_image")
            fun u8(at: Int): Int = prefix.getOrNull(at)?.toInt()?.and(255) ?: invalid()
            fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
            fun be32(at: Int): Long = (0..3).fold(0L) { result, n -> (result shl 8) or u8(at + n).toLong() }
            fun le32(at: Int): Long = (0..3).fold(0L) { result, n -> result or (u8(at + n).toLong() shl (8 * n)) }
            fun text(at: Int, value: String): Boolean = value.indices.all { prefix.getOrNull(at + it)?.toInt()?.and(255) == value[it].code }
            val dimensions: Pair<Int, Int> = when (mime) {
                "image/png" -> {
                    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
                    if (prefix.size < 33 || !prefix.copyOfRange(0, 8).contentEquals(signature) ||
                        be32(8) != 13L || !text(12, "IHDR") || u8(26) != 0 || u8(27) != 0 || u8(28) !in 0..1 ||
                        !tail.contentEquals(byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126))) invalid()
                    val allowedDepths = when (u8(25)) {
                        0 -> setOf(1, 2, 4, 8, 16); 2, 4, 6 -> setOf(8, 16); 3 -> setOf(1, 2, 4, 8); else -> invalid()
                    }
                    if (u8(24) !in allowedDepths) invalid()
                    // APNG's animation declaration precedes image data. Do not hand an unbounded
                    // animated image to a native decoder or parse arbitrarily large metadata.
                    var offset = 33
                    while (!text(offset + 4, "IDAT")) {
                        if (text(offset + 4, "acTL")) artworkFailure(FailureCode.UNSUPPORTED_SOURCE, "animated_artwork_not_supported")
                        val length = be32(offset)
                        if (length > PREFIX_BYTES || offset.toLong() + length + 12 > prefix.size) invalid()
                        offset += length.toInt() + 12
                    }
                    if (be32(offset) > size - offset - 12) invalid()
                    val width = be32(16); val height = be32(20)
                    if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) invalid()
                    width.toInt() to height.toInt()
                }
                "image/jpeg" -> {
                    if (u8(0) != 255 || u8(1) != 216 || tail.size < 2 ||
                        tail[tail.lastIndex - 1].toInt().and(255) != 255 || tail.last().toInt().and(255) != 217) invalid()
                    var offset = 2
                    var found: Pair<Int, Int>? = null
                    while (found == null) {
                        if (u8(offset++) != 255) invalid()
                        while (u8(offset) == 255) offset++
                        val marker = u8(offset++)
                        if (marker in setOf(0, 216, 217, 218)) invalid()
                        if (marker == 1 || marker in 208..215) continue
                        val length = u16(offset)
                        if (length < 2 || offset.toLong() + length > prefix.size) invalid()
                        if (marker in setOf(192, 193, 194)) {
                            if (length < 8 || u8(offset + 2) != 8 || u8(offset + 7) !in 1..4) invalid()
                            found = u16(offset + 5) to u16(offset + 3)
                        }
                        offset += length
                    }
                    found
                }
                "image/webp" -> {
                    if (!text(0, "RIFF") || !text(8, "WEBP") || le32(4) + 8 != size || le32(16) > size - 20) invalid()
                    when {
                        text(12, "VP8X") -> {
                            if (le32(16) != 10L || u8(20) and 2 != 0) artworkFailure(FailureCode.UNSUPPORTED_SOURCE, "animated_artwork_not_supported")
                            val width = u8(24) or (u8(25) shl 8) or (u8(26) shl 16)
                            val height = u8(27) or (u8(28) shl 8) or (u8(29) shl 16)
                            (width + 1) to (height + 1)
                        }
                        text(12, "VP8 ") -> {
                            if (le32(16) < 10 || u8(20) and 1 != 0 || u8(23) != 157 || u8(24) != 1 || u8(25) != 42) invalid()
                            ((u8(26) or (u8(27) shl 8)) and 0x3fff) to ((u8(28) or (u8(29) shl 8)) and 0x3fff)
                        }
                        text(12, "VP8L") -> {
                            if (le32(16) < 5 || u8(20) != 47) invalid()
                            val bits = le32(21)
                            if (bits ushr 29 != 0L) invalid()
                            ((bits and 0x3fff) + 1).toInt() to (((bits ushr 14) and 0x3fff) + 1).toInt()
                        }
                        else -> invalid()
                    }
                }
                else -> invalid()
            }
            val (width, height) = dimensions
            if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 16_777_216) {
                artworkFailure(FailureCode.PARSE_FAILURE, "artwork_dimension_limit")
            }
            return ArtworkImage(mime, when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; else -> "webp" }, width, height)
        }
    }
}

internal fun artworkFailure(code: FailureCode, detail: String): Nothing = throw VeyraException(AppFailure(code, detail))
