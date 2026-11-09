package dev.veyra.downloads

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.model.newEntityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** No global graph pin: this registry belongs to exactly one coordinator (or standalone Files). */
internal class DownloadCleanupRegistry(private val changed: () -> Unit = {}) {
    val owners = MutableStateFlow<Map<String, DownloadCleanupOwner>>(emptyMap())

    fun register(owner: DownloadCleanupOwner) {
        owners.update { current ->
            check(owner.token !in current)
            current + (owner.token to owner)
        }
        changed()
    }

    fun remove(owner: DownloadCleanupOwner) {
        owners.update { current ->
            if (current[owner.token] === owner) current - owner.token else current
        }
        changed()
    }
}

internal sealed class DownloadCleanupOwner(val token: String) {
    val lock = Mutex()
    val failed = MutableStateFlow(false)
    var nextStep = 0 // Only the serialized owner advances this, AFTER successful return.
}

/**
 * Ordered file-only ownership. Successful members are never closed twice. Initial synchronous
 * acquisition compensation has one writer; failed records become eligible for explicit close
 * only after their acquiring job/admission has returned. Coordinator retries hold [lock].
 */
internal class DownloadLeaseCleanup(private val leases: List<DownloadFileLease>) :
    DownloadCleanupOwner(newEntityId("download-lease-cleanup")), DownloadFileLease {
    override fun close() = closeThrough(leases.size)

    fun closeThrough(end: Int) {
        require(end in nextStep..leases.size)
        try {
            while (nextStep < end) {
                leases[nextStep].close()
                nextStep++
            }
        } catch (error: Throwable) {
            failed.value = true
            throw error
        }
    }
}

/** A failed native acquire may itself own an unconsumed descriptor; never lose it in classification. */
internal class DownloadFileLeaseAcquisitionFailure(val lease: DownloadFileLease) :
    Exception("download_lock_release")

internal fun downloadLockReleaseFailure() = VeyraException(AppFailure(FailureCode.STORAGE_IO, "download_lock_release"))

/**
 * One acquisition's temporary owners, using the SAME coordinator registry as normal finalizers.
 * The scope is local to the operation, not DownloadFiles: two coordinators must not adopt each
 * other's native leases. Registered leftovers are adopted by the enclosing rollback/finalizer.
 */
internal class DownloadLeaseCleanupScope(private val registry: DownloadCleanupRegistry) {
    private val registered = mutableListOf<DownloadLeaseCleanup>()

    fun acquire(factory: DownloadFileLeaseFactory, path: String): DownloadFileLease? = try {
        factory.acquire(path)
    } catch (error: DownloadFileLeaseAcquisitionFailure) {
        retain(DownloadLeaseCleanup(listOf(error.lease)).also { it.failed.value = true })
        throw downloadLockReleaseFailure()
    }

    /** [handedOff] is released to the caller ONLY after all temporary closes returned. */
    fun releaseTemporary(temporary: List<DownloadFileLease>, handedOff: List<DownloadFileLease> = emptyList()) {
        if (temporary.isEmpty()) return
        val cleanup = DownloadLeaseCleanup(temporary + handedOff)
        retain(cleanup) // Before the first fallible close, including its dependent handoff.
        cleanup.closeThrough(temporary.size)
        registry.remove(cleanup)
    }

    fun pending(): List<DownloadLeaseCleanup> = registered.filter { registry.owners.value[it.token] === it }

    /** Register the replacing owner FIRST; never create an unowned gap. */
    fun adopted() { pending().forEach(registry::remove) }

    private fun retain(cleanup: DownloadLeaseCleanup) {
        registry.register(cleanup)
        registered += cleanup
    }
}
