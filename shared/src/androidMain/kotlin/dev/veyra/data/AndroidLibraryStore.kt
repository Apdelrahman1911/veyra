package dev.veyra.data

import android.content.Context
import androidx.room.Room
import dev.veyra.data.room.VeyraDatabase

fun createAndroidLibraryStore(context: Context, directory: String): LibraryStore = createRoomLibraryStore(directory, { path ->
    Room.databaseBuilder(context.applicationContext, VeyraDatabase::class.java, path)
})
