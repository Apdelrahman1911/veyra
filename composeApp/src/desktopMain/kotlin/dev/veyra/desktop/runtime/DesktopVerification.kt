package dev.veyra.desktop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal enum class CentralProofPhase { ONLINE, OFFLINE }

internal data class DesktopOptions(
    val dataDirectory: Path = DesktopProcessContext.defaultDirectory(),
    val fixture: Path? = null,
    val result: Path? = null,
    val silentAudio: Boolean = false,
    val uiSmoke: Boolean = false,
    val centralProof: CentralProofPhase? = null,
) {
    val verification: Boolean get() = fixture != null || uiSmoke || centralProof != null
    val verificationKind: String get() = when {
        centralProof != null -> "Veyra-D005-central-listening"
        uiSmoke -> "ui-smoke"
        else -> "native-self-test"
    }
}

internal fun parseDesktopOptions(args: Array<String>): DesktopOptions {
    var value = DesktopOptions()
    val seen = mutableSetOf<String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        val unique = if (flag == "--self-test-result") "--verification-result" else flag
        require(seen.add(unique)) { "Duplicate option" }
        fun argument(): String {
            index++
            require(index < args.size && args[index].isNotBlank() && !args[index].startsWith("--")) { "Missing option value" }
            return args[index]
        }
        fun path(): Path = Path.of(argument()).toAbsolutePath().normalize()
        value = when (flag) {
            "--data-dir" -> value.copy(dataDirectory = path())
            "--self-test" -> value.copy(fixture = path())
            "--self-test-result", "--verification-result" -> value.copy(result = path())
            "--silent-audio" -> value.copy(silentAudio = true)
            "--ui-smoke" -> value.copy(uiSmoke = true)
            "--central-proof" -> value.copy(centralProof = when (argument()) {
                "online" -> CentralProofPhase.ONLINE
                "offline" -> CentralProofPhase.OFFLINE
                else -> error("Unknown proof phase")
            })
            else -> error("Unknown option")
        }
        index++
    }
    require(listOf(value.fixture != null, value.uiSmoke, value.centralProof != null).count { it } <= 1) { "Verification modes are exclusive" }
    require(value.centralProof == null || "--data-dir" in seen) { "Central proof requires an explicit dedicated data directory" }
    require(value.result == null || value.verification) { "A result requires a verification mode" }
    value.result?.let { requireDesktopResultPath(value.dataDirectory, it) }
    return value
}

/** Only an immediate JSON child of this reserved results directory is writable by the CLI.
 * Room, runtime executables, lock files and the online proof marker are never result targets. */
internal fun requireDesktopResultPath(dataDirectory: Path, result: Path): Path {
    val base = dataDirectory.toAbsolutePath().normalize().resolve("verification/results")
    val destination = result.toAbsolutePath().normalize()
    val name = destination.fileName?.toString().orEmpty()
    require(destination.parent == base && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.json"))) {
        "Verification results must be JSON files directly inside verification/results"
    }
    val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    require(name.substringBefore('.').uppercase(java.util.Locale.ROOT) !in reserved) { "Reserved result filename" }
    DesktopProcessContext.rejectSymbolicAncestors(destination)
    return destination
}

internal data class DesktopVerificationOutcome(val report: String, val success: Boolean, val ownersClosed: Boolean)

/** No caller may publish the candidate report before this full, non-timeout cleanup barrier. */
internal suspend fun finishDesktopVerification(candidate: String, closeOwners: suspend () -> Unit): DesktopVerificationOutcome {
    var closed = false
    try { closeOwners(); closed = true } catch (_: Throwable) { /* Static result below; never disclose exception text. */ }
    val parsed = if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_DESKTOP_REPORT_BYTES) {
        runCatching { Json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
    } else null
    val operationSucceeded = (parsed?.get("success") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == true } == true
    val success = operationSucceeded && closed
    val report = JsonObject((parsed?.toMap() ?: mapOf("failure" to JsonPrimitive("invalid_verification_report"))) + buildMap {
        put("success", JsonPrimitive(success))
        put("ownersClosedByHost", JsonPrimitive(closed))
        put("physicalAudioVerified", JsonPrimitive(false))
        if (!closed) {
            put("cleanupFailure", JsonPrimitive("runtime_resource_cleanup_failed"))
            if (parsed?.containsKey("newProcessOfflineStageVerified") == true)
                put("newProcessOfflineStageVerified", JsonPrimitive(false))
        }
    }).toString()
    return DesktopVerificationOutcome(report, success, closed)
}

internal fun desktopVerificationFailure(options: DesktopOptions, reason: String): String = buildJsonObject {
    put("success", false)
    put("proof", options.verificationKind)
    options.centralProof?.let { put("phase", it.name.lowercase(java.util.Locale.ROOT)) }
    put("silentAudio", options.silentAudio)
    put("physicalAudioVerified", false)
    put("failure", reason)
}.toString()

/** Called only after all owners, including the process context, have finished closing.
 * The old context is used solely for canonical path validation, not as a live lock owner. */
internal fun writeDesktopVerificationResult(context: DesktopProcessContext, result: Path?, report: String) {
    require(report.toByteArray(Charsets.UTF_8).size <= MAX_DESKTOP_REPORT_BYTES) { "Verification report is too large" }
    if (result == null) return
    val destination = context.ownedPath(requireDesktopResultPath(context.dataDirectory, result))
    DesktopProcessContext.privateDirectory(destination.parent.parent)
    DesktopProcessContext.privateDirectory(destination.parent)
    require(!Files.exists(destination, NOFOLLOW_LINKS) || Files.isRegularFile(destination, NOFOLLOW_LINKS)) { "Result target is not a regular file" }
    val staging = Files.createTempFile(destination.parent, ".result-", ".json")
    try {
        FileChannel.open(staging, WRITE, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap((report + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        requireDesktopResultPath(context.dataDirectory, destination)
        Files.move(staging, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(staging) }
}

internal fun desktopResultWriteFailure(report: String): String {
    val fields = (runCatching { Json.parseToJsonElement(report) as? JsonObject }.getOrNull()?.toMap()).orEmpty()
    return JsonObject(fields + mapOf("success" to JsonPrimitive(false),
        "resultWriteFailure" to JsonPrimitive("verification_result_write_failed"))).toString()
}

private const val MAX_DESKTOP_REPORT_BYTES = 512 * 1024
