package dev.veyra.artwork

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import okio.ByteString.Companion.decodeHex
import kotlin.test.*

/** Authored byte/header fixtures. Header acceptance is not a claim of native image decoding. */
class ArtworkImageTest {
    private fun inspect(bytes: ByteArray, mime: String) = ArtworkImage.inspect(
        bytes, bytes.takeLast(12).toByteArray(), bytes.size.toLong(), mime,
    )

    @Test fun acceptsStaticPngWithBoundedDimensions() {
        val image = inspect(authoredArtworkPng(), "image/png; charset=binary")
        assertEquals("png", image.extension)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
    }

    @Test fun rejectsAudioHtmlSvgAndMimeSignatureMismatch() {
        for ((bytes, mime) in listOf(
            "ID3 authored non-audio fixture".encodeToByteArray() to "image/jpeg",
            "<html>not an image</html>".encodeToByteArray() to "image/png",
            "<svg/>".encodeToByteArray() to "image/svg+xml",
            authoredArtworkPng() to "audio/mpeg",
            authoredArtworkPng() to "image/webp",
        )) assertFailsWith<VeyraException> { inspect(bytes, mime) }
    }

    @Test fun rejectsTruncationAndExcessivePixelDimensions() {
        assertFailsWith<VeyraException> { inspect(authoredArtworkPng().dropLast(1).toByteArray(), "image/png") }
        val oversized = authoredArtworkPng().apply { this[18] = 0x20; this[19] = 0 }
        assertEquals(FailureCode.PARSE_FAILURE, assertFailsWith<VeyraException> {
            inspect(oversized, "image/png")
        }.failure.code)
    }

    @Test fun rejectsPngAnimationDeclarationBeforeImageData() {
        val image = authoredArtworkPng()
        val animation = "000000086163544c000000010000000000000000".decodeHex().toByteArray()
        val animated = image.copyOfRange(0, 33) + animation + image.copyOfRange(33, image.size)
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> {
            inspect(animated, "image/png")
        }.failure.code)
    }

    @Test fun jpegHeaderDimensionsAreBoundedWithoutReadingUnboundedMetadata() {
        // Deliberately only a structural fixture, NOT a complete decodable JPEG image.
        val structural = "ffd8ffc0000b080010002001011100ffd9".decodeHex().toByteArray()
        val image = inspect(structural, "image/jpeg")
        assertEquals(32, image.width)
        assertEquals(16, image.height)
        assertFailsWith<VeyraException> { inspect(structural.copyOfRange(0, 8), "image/jpeg") }
    }

    @Test fun webpContainerLengthAndAnimationFlagAreChecked() {
        // Deliberately only a structural fixture, not native-decoder evidence.
        val structural = "524946461600000057454250565038580a000000000000000f00001f0000".decodeHex().toByteArray()
        val image = inspect(structural, "image/webp")
        assertEquals(16, image.width)
        assertEquals(32, image.height)
        val animated = structural.copyOf().apply { this[20] = 2 }
        assertEquals(FailureCode.UNSUPPORTED_SOURCE, assertFailsWith<VeyraException> {
            inspect(animated, "image/webp")
        }.failure.code)
        assertFailsWith<VeyraException> { inspect(structural + byteArrayOf(0), "image/webp") }
    }

    @Test fun sizeLimitIsEnforcedIndependentlyOfHeaders() {
        val png = authoredArtworkPng()
        assertFailsWith<VeyraException> {
            ArtworkImage.inspect(png, png.takeLast(12).toByteArray(), ArtworkImage.MAX_BYTES + 1, "image/png")
        }
    }

    @Test fun artworkRootRejectsRelativeAndRemoteFileSystemLocatorsWithoutIo() {
        for (path in listOf("relative/cache", "file:///owned/cache", "\\\\server\\share\\cache", "//server/share/cache", "/")) {
            assertFailsWith<IllegalArgumentException> { ArtworkFiles(path) }
        }
    }
}

/** Authored 1x1 transparent PNG, redistributable test pixels, no remote acquisition. */
internal fun authoredArtworkPng(): ByteArray =
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000b49444154789c6360000200000500017a5eab3f0000000049454e44ae426082"
        .decodeHex().toByteArray()
