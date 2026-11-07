package dev.veyra.acquisition

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AcquisitionInputRulesTest {
    @Test fun completeArabicUnicodeAndSurroundingSpacesAreNotNormalized() {
        val title = "  ڤيرا / e\u0301 — \uD83C\uDFB5 (Edition 2)  "
        assertEquals(title, AcquisitionInputRules.completeTitle(title))
    }

    @Test fun punctuationMarkupLikeTextAndBidiMarksAreNotRewritten() {
        val title = "\u200F<أمواج> & \"Quiet\" / 2026 — الجزء الأول\u200E"
        assertEquals(title, AcquisitionInputRules.completeTitle(title))
    }

    @Test fun maximumLengthIsAcceptedWithoutEllipsis() {
        val title = "س".repeat(AcquisitionInputRules.MAX_COMPLETE_TITLE_LENGTH)
        assertEquals(title, AcquisitionInputRules.completeTitle(title))
    }

    @Test fun emptyBlankAndOverlongValuesAreRejectedNotTrimmedOrTruncated() {
        listOf("", "   ", "\u2003", "a".repeat(AcquisitionInputRules.MAX_COMPLETE_TITLE_LENGTH + 1)).forEach(::reject)
    }

    @Test fun controlCharactersCannotEnterAQuery() {
        (0..31).plus(127..159).forEach { reject("title${it.toChar()}tail") }
    }

    @Test fun malformedUtf16IsRejected() {
        listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800", "\uDC00\uD800").forEach(::reject)
    }

    @Test fun providerRevisionIsOptionalCompleteAndNotNormalized() {
        assertNull(AcquisitionInputRules.providerRevision(null))
        val revision = "  revision — الإصدار \uD83C\uDFB5  "
        assertEquals(revision, AcquisitionInputRules.providerRevision(revision))
        val maximum = "r".repeat(AcquisitionInputRules.MAX_PROVIDER_REVISION_LENGTH)
        assertEquals(maximum, AcquisitionInputRules.providerRevision(maximum))
    }

    @Test fun malformedBlankOverlongAndControlBearingProviderRevisionsAreRejected() {
        for (revision in listOf("", " ", "r".repeat(513), "r\n1", "r\u0080", "r\uD800")) {
            assertEquals("acquisition_provider_revision_invalid", assertFailsWith<VeyraException> {
                AcquisitionInputRules.providerRevision(revision)
            }.failure.detail)
        }
    }

    private fun reject(title: String) {
        val failure = assertFailsWith<VeyraException> { AcquisitionInputRules.completeTitle(title) }.failure
        assertEquals(FailureCode.INVALID_STATE, failure.code)
        assertEquals("acquisition_complete_title_required", failure.detail)
    }
}
