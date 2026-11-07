package dev.veyra.acquisition

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException

/** Admission rules, not display formatting. Accepted source queries are never normalized. */
object AcquisitionInputRules {
    const val MAX_COMPLETE_TITLE_LENGTH: Int = 16_384
    const val MAX_PROVIDER_REVISION_LENGTH: Int = 512

    /** Returns [value] verbatim or fails before either persistence admission or source I/O. */
    fun completeTitle(value: String): String {
        if (!completeText(value, MAX_COMPLETE_TITLE_LENGTH)) throw VeyraException(
            AppFailure(FailureCode.INVALID_STATE, "acquisition_complete_title_required"),
        )
        return value
    }

    /** Optional opaque public correlation, not source-version or provider-atomicity evidence. */
    fun providerRevision(value: String?): String? {
        if (value != null && !completeText(value, MAX_PROVIDER_REVISION_LENGTH)) throw VeyraException(
            AppFailure(FailureCode.INVALID_STATE, "acquisition_provider_revision_invalid"),
        )
        return value
    }

    private fun completeText(value: String, maximum: Int): Boolean {
        if (value.length !in 1..maximum || value.isBlank()) return false
        var offset = 0
        while (offset < value.length) {
            val character = value[offset++]
            if (character.code < 32 || character.code in 127..159) return false
            if (character.isHighSurrogate()) {
                if (offset == value.length || !value[offset++].isLowSurrogate()) return false
            } else if (character.isLowSurrogate()) return false
        }
        return true
    }
}
