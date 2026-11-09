package dev.veyra.app

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.io.File

/** Run off Main. Resolve OS-owned aliases only; never follow an app-owned Veyra symlink. */
internal fun androidApplicationDirectory(filesDirectory: File): File {
    val trustedBase = filesDirectory.canonicalFile
    val directory = File(trustedBase, "veyra")
    fun requireOwnedRoot() {
        if (directory.canonicalPath != directory.absolutePath) {
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory_symlink"))
        }
    }
    requireOwnedRoot()
    if (!directory.isDirectory && !directory.mkdirs()) {
        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "application_directory"))
    }
    requireOwnedRoot()
    return directory
}
