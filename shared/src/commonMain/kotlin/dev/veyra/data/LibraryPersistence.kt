package dev.veyra.data

import dev.veyra.model.AppSnapshot
import dev.veyra.data.room.AcquisitionGraph
import kotlinx.coroutines.flow.Flow

internal data class OpenedLibrary(
    val snapshot: AppSnapshot,
    val recoveredFromBackup: Boolean = false,
    /** Non-null for the only production Room backend; null is retained for old test seams. */
    val acquisitionGraph: AcquisitionGraph? = null,
)

/** Test seam, not an alternate production storage backend. Production uses RoomLibraryPersistence. */
internal interface LibraryPersistence {
    suspend fun open(): OpenedLibrary
    suspend fun read(): AppSnapshot
    suspend fun transact(expectedRevision: Long?, transform: (AppSnapshot) -> AppSnapshot): AppSnapshot
    fun changes(): Flow<Unit>
    suspend fun backup()
    suspend fun recoverFromBackup(): OpenedLibrary
    suspend fun close()
}
