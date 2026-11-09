package dev.veyra.data

import androidx.room.Room
import dev.veyra.data.room.VeyraDatabase
import dev.veyra.data.room.VeyraDatabaseConstructor

fun createPlatformLibraryStore(directory: String): LibraryStore = createRoomLibraryStore(directory, { path ->
    Room.databaseBuilder<VeyraDatabase>(name = path, factory = VeyraDatabaseConstructor::initialize)
})
