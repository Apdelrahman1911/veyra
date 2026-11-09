package dev.veyra.data

import dev.veyra.model.AppSnapshot

/** Both detached projections were read from one database transaction under the one owner. */
data class LibraryAcquisitionView(val snapshot: AppSnapshot, val acquisition: AcquisitionRepositoryState) {
    init { require(snapshot.revision == acquisition.libraryRevision) }
}
