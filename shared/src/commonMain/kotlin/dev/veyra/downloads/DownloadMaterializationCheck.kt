package dev.veyra.downloads

import dev.veyra.model.MaterializationAsset

/**
 * One successful physical check in THIS runtime. Equality is deliberately reference identity:
 * a successful recheck of the exact same immutable asset issues a different instance. Never
 * persist this value or infer success from a Room revision/maintenance ownership generation.
 *
 * This is NOT an AssetUse, playback grant, command-completion or native-reader/lease-drain ACK.
 * The existing exact member/claim/receipt/use/file and owner checks remain independently required.
 * A physical success may be visible while that operation is still awaiting owner cleanup.
 */
class DownloadMaterializationCheck internal constructor(
    val downloadId: String,
    val asset: MaterializationAsset,
) {
    override fun toString() = "DownloadMaterializationCheck(processLocal=true,identity=redacted)"
}

/**
 * One immutable flow value. History and CURRENT validity are atomically paired, not synonyms:
 * an actual negative recheck removes validity but retains the last successful reference C0.
 * Only a physical success may issue C1. Readiness/intent/cleanup changes cannot resurrect C0
 * as if it were a fresh success. No persistent row, AssetUse or local/native grant is changed.
 */
internal class DownloadMaterializationChecks private constructor(
    val successfulChecks: Map<String, DownloadMaterializationCheck>,
    val currentValidAssets: Map<String, MaterializationAsset>,
) {
    /** Existing internal consumers read current validity, NOT the set of historical checks. */
    val assets: Map<String, MaterializationAsset> get() = currentValidAssets

    /** Physical runtime success sites only; a projector/resolver must never mint a reference. */
    internal fun recordSuccess(downloadId: String, asset: MaterializationAsset): DownloadMaterializationChecks =
        DownloadMaterializationChecks(
            successfulChecks + (downloadId to DownloadMaterializationCheck(downloadId, asset)),
            currentValidAssets + (downloadId to asset),
        )

    /** Exact admitted C0, not merely equal asset IDs; a late C0 cannot revoke a newer C1. */
    internal fun rejected(downloadId: String, asset: MaterializationAsset,
        successfulCheck: DownloadMaterializationCheck?): DownloadMaterializationChecks =
        if (successfulCheck == null || successfulChecks[downloadId] !== successfulCheck ||
            successfulCheck.asset != asset || currentValidAssets[downloadId] != asset) this
        else DownloadMaterializationChecks(successfulChecks, currentValidAssets - downloadId)

    /** Value helper retained for source compatibility; runtime deletion uses [withoutExact]. */
    internal fun without(downloadId: String): DownloadMaterializationChecks =
        if (downloadId !in successfulChecks) this
        else DownloadMaterializationChecks(successfulChecks - downloadId, currentValidAssets - downloadId)

    internal fun withoutExact(downloadId: String, asset: MaterializationAsset): DownloadMaterializationChecks =
        if (successfulChecks[downloadId]?.asset != asset) this else without(downloadId)

    override fun toString() = "DownloadMaterializationChecks(history=${successfulChecks.size},valid=${currentValidAssets.size},identity=redacted)"

    companion object {
        val Empty = DownloadMaterializationChecks(emptyMap(), emptyMap())
    }
}
