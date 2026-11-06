package dev.veyra.data

import androidx.room.RoomDatabase
import dev.veyra.data.room.RoomDatabaseFiles
import dev.veyra.data.room.RoomLibraryPersistence
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.buildVeyraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Platform factories supply only Room's platform builder; transactions/recovery stay shared. */
fun createRoomLibraryStore(
    directory: String,
    builderFactory: (path: String) -> RoomDatabase.Builder<VeyraDatabase>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): LibraryStore = LibraryStore(
    RoomLibraryPersistence({ path -> buildVeyraDatabase(builderFactory(path), dispatcher) }, RoomDatabaseFiles(directory)),
    dispatcher,
)
