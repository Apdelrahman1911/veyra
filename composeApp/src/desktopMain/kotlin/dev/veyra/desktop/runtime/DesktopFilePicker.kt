package dev.veyra.desktop.runtime

import dev.veyra.app.ImportedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.coroutineContext

/** The picker grants only the selected regular files; import copies stream into app-owned staging. */
class DesktopFilePicker(private val context: DesktopProcessContext) {
    suspend fun chooseAudioFiles(): List<ImportedAudio> {
        val chosen = withContext(Dispatchers.Main) {
            val dialog = JFileChooser().apply {
                isMultiSelectionEnabled = true
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter("Audio · صوت", "mp3", "m4a", "aac", "webm", "ogg", "opus", "flac")
            }
            if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) dialog.selectedFiles.map { it.toPath().toRealPath() }
            else emptyList()
        }
        return chosen.take(1000).filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && Files.isReadable(it) }.map { source ->
            ImportedAudio(source.fileName.toString()) { destination -> copy(source, context.ownedPath(Path.of(destination))) }
        }
    }

    private suspend fun copy(source: Path, destination: Path) = withContext(Dispatchers.IO) {
        // The root allocates the stage name. Refuse existing/symlink destinations instead of truncating.
        DesktopProcessContext.privateDirectory(destination.parent)
        var created = false
        try {
            Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                Files.newOutputStream(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                    created = true
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Throwable) {
            if (created) Files.deleteIfExists(destination)
            throw error
        }
    }
}
