package dev.veyra.desktop.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

@Serializable
internal data class HelperFile(
    val path: String,
    val size: Long,
    val sha256: String? = null,
    val link: String? = null,
    val executable: Boolean = false,
)

@Serializable
internal data class HelperManifest(
    val platform: String,
    val component: String,
    val version: String,
    val url: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val expandedBytes: Long,
    val archiveRoot: String,
    val format: String,
    val executable: String,
    val selection: Map<String, String> = emptyMap(),
    val entries: List<HelperFile>,
)

/** File hashes are derived from the whole-pinned official archives, packaged with signed app
 * code, and never loaded from the mutable installation directory. See native/runtime/. */
internal object DesktopHelperManifests {
    private val hashes = mapOf(
        "node-linux-x64" to "56cc0dee86e5540d7dd9df257f63d360da506b42b5382e33d77ad2c7a8b5538c",
        "node-windows-x64" to "8941deccd63f60b46fb77ffa36d1d5d306118413dab486319809a3071285bdbd",
        "node-macos-x64" to "23f6adecf867f5434da3ac0fec28432562a613c1813a4cfb69f81f60cc589b61",
        "node-macos-arm64" to "d32fbf06f1743034a4de3f33a6794058eeb37e4b99dac31815faae54c90170cb",
        "chromium-linux-x64" to "5b0aa8b31bc1c960dd6fb498d3243ccd60b0eb535991c224f8066e920f65e140",
        "chromium-windows-x64" to "d9beef7c9e07adf50d2912f85e3dfe30bb0f23d20536c54675e35576c439f42e",
        "chromium-macos-x64" to "874fdc9b15dc5f067d48494cf73eb14715ff4f2b9c44a4af44c1f8dac3a92f13",
        "chromium-macos-arm64" to "6c3b9cf3f642a111cfb642b251898f6cbbf1d955fce3fddd125ef7f8d2a86cad",
    )

    fun load(component: String, platform: String): HelperManifest {
        val key = "$component-$platform"
        val expected = hashes[key] ?: error("No pinned helper for this platform")
        val bytes = checkNotNull(javaClass.getResourceAsStream("/dev/veyra/desktop/helpers/$key.json"))
            .use { it.readNBytes(1_048_577) }
        require(bytes.size in 1..1_048_576 && hash(bytes) == expected) { "Helper inventory integrity check failed" }
        return Json.decodeFromString<HelperManifest>(bytes.decodeToString()).also { manifest ->
            require(manifest.component == component && manifest.platform == platform)
            require(manifest.archiveBytes in 1..536_870_912 && manifest.expandedBytes in 1..1_073_741_824)
            require(manifest.format in setOf("zip", "tar.gz") && digestPattern.matches(manifest.archiveSha256))
            require(manifest.entries.isNotEmpty() && manifest.entries.size <= 20_000)
            SafeHelperArchive.relative(manifest.archiveRoot)
            require('/' !in manifest.archiveRoot)
            require(manifest.entries.map { it.path.lowercase(Locale.ROOT) }.distinct().size == manifest.entries.size)
            manifest.entries.forEach { entry ->
                SafeHelperArchive.relative(entry.path)
                require(entry.size in 0..1_073_741_824)
                require((entry.sha256 != null) != (entry.link != null))
                entry.sha256?.let { require(digestPattern.matches(it)) }
                entry.link?.let { require(entry.size == 0L && !entry.executable) }
            }
            require(manifest.entries.any { it.path == manifest.executable && it.sha256 != null && it.executable })
            manifest.selection.forEach { (from, to) -> SafeHelperArchive.relative(from); SafeHelperArchive.relative(to) }
            val uri = java.net.URI(manifest.url)
            require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null)
            require(uri.host == if (component == "node") "nodejs.org" else "storage.googleapis.com")
        }
    }

    internal val digestPattern = Regex("[a-f0-9]{64}")
    internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
