package dev.veyra.desktop.runtime

import dev.veyra.app.MediaRuntimeComponent
import dev.veyra.app.MediaRuntimePhase
import dev.veyra.app.MediaRuntimeStatus
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreparedMediaTools(val node: Path, val chromium: Path, val ffmpeg: Path, val temporaryDirectory: Path)

internal fun interface HelperArchiveFetcher {
    /** Writes a NEW file; caller independently verifies the pinned whole archive afterwards. */
    suspend fun fetch(manifest: HelperManifest, destination: Path, progress: (Long) -> Unit)
}

/** App-owned extraction dependencies, not an alternate media acquisition path. Constructing this
 * object performs no network requests or helper installation. Only the shared cancellable online
 * gate calls prepare(); local/offline playback never needs these tools or the network. */
internal class DesktopMediaRuntime(
    private val context: DesktopProcessContext,
    private val platform: String = DesktopNativeRuntime.platform(),
    private val manifestProvider: (String, String) -> HelperManifest = DesktopHelperManifests::load,
    private val suppliedFetcher: HelperArchiveFetcher? = null,
    private val suppliedFfmpeg: (suspend () -> Path)? = null,
) {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private val installLock = Mutex()
    private val closing = AtomicBoolean()
    @Volatile private var prepared: PreparedMediaTools? = null
    private val httpDelegate = lazy {
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 900_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        }
    }
    private val http by httpDelegate
    private val mutableStatus = MutableStateFlow(MediaRuntimeStatus(MediaRuntimePhase.REQUIRED, MediaRuntimeComponent.NODE))
    val status = mutableStatus.asStateFlow()

    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        check(!closing.get()) { "Media runtime has closed" }
        if (prepared != null) return
        // This owned child gives close() a cancellation/join barrier. Caller cancellation also
        // cancels/joins it, so an offline-policy switch cannot leave a detached installation.
        val operation = scope.async {
            installLock.withLock {
                if (prepared != null) return@withLock
                try {
                    mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.VERIFYING, MediaRuntimeComponent.FFMPEG)
                    // Missing packaged components fail before downloading hundreds of helper MB.
                    val ffmpeg = suppliedFfmpeg?.invoke() ?: installBundledFfmpeg()
                    val node = install(manifestProvider("node", platform))
                    val chromium = install(manifestProvider("chromium", platform))
                    val temporary = context.ownedPath(context.dataDirectory.resolve("tmp/kmpyt"))
                    DesktopProcessContext.privateDirectory(temporary)
                    currentCoroutineContext().ensureActive()
                    prepared = PreparedMediaTools(node, chromium, ffmpeg, temporary)
                    mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.READY)
                } catch (cancelled: CancellationException) {
                    mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.REQUIRED, mutableStatus.value.component)
                    throw cancelled
                } catch (error: Exception) {
                    val failure = (error as? VeyraException)?.failure ?: AppFailure(
                        if (mutableStatus.value.phase == MediaRuntimePhase.DOWNLOADING) FailureCode.NETWORK_UNAVAILABLE else FailureCode.RECOVERY_REQUIRED,
                        "media_runtime_setup_failed")
                    mutableStatus.value = mutableStatus.value.copy(phase = MediaRuntimePhase.ERROR, failure = failure)
                    throw VeyraException(failure)
                }
            }
        }
        try { operation.await() }
        finally { withContext(NonCancellable) { operation.cancelAndJoin() } }
    }

    /** Synchronous JS runtime setup may only consume a previously verified installation. */
    fun requirePrepared(): PreparedMediaTools {
        check(!closing.get()) { "Media runtime has closed" }
        return prepared ?: throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "media_runtime_setup_required"))
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        closing.set(true)
        lifetime.cancelAndJoin()
        if (httpDelegate.isInitialized()) http.close()
        prepared = null
    }

    private suspend fun install(manifest: HelperManifest): Path {
        val component = MediaRuntimeComponent.valueOf(manifest.component.uppercase(Locale.ROOT))
        require(component in setOf(MediaRuntimeComponent.NODE, MediaRuntimeComponent.CHROMIUM))
        require(DesktopHelperManifests.digestPattern.matches(manifest.archiveSha256))
        val parent = context.ownedPath(context.dataDirectory.resolve("runtime/helpers/${manifest.component}"))
        DesktopProcessContext.privateDirectory(parent)
        // ProcessContext holds the one-app data-directory lock. Only incomplete stages owned by
        // this installer are eligible for restart cleanup; never delete live/versioned helpers.
        Files.list(parent).use { children ->
            children.filter { it.fileName.toString().startsWith(".setup-") }.forEach { deleteTree(it) }
        }
        val destination = parent.resolve(manifest.archiveSha256)
        mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.VERIFYING, component)
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            // Never overwrite loaded code or silently trust a mutable on-disk hash receipt.
            SafeHelperArchive.verify(destination, manifest)
            return destination.resolve(manifest.executable)
        }
        val needed = manifest.archiveBytes + manifest.entries.sumOf { it.size } + 67_108_864L
        if (Files.getFileStore(parent).usableSpace < needed) throw VeyraException(AppFailure(FailureCode.STORAGE_FULL, "media_runtime_storage"))
        val stage = Files.createTempDirectory(parent, ".setup-")
        DesktopProcessContext.privateDirectory(stage)
        try {
            val archive = stage.resolve("download.archive")
            mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.DOWNLOADING, component, totalBytes = manifest.archiveBytes)
            (suppliedFetcher ?: HelperArchiveFetcher(::download)).fetch(manifest, archive) { count ->
                mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.DOWNLOADING, component, count, manifest.archiveBytes)
            }
            currentCoroutineContext().ensureActive()
            mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.VERIFYING, component, manifest.archiveBytes, manifest.archiveBytes)
            require(Files.isRegularFile(archive, NOFOLLOW_LINKS) && Files.size(archive) == manifest.archiveBytes &&
                SafeHelperArchive.hashFile(archive) == manifest.archiveSha256) { "Downloaded helper archive integrity check failed" }
            val expanded = stage.resolve("expanded")
            mutableStatus.value = MediaRuntimeStatus(MediaRuntimePhase.INSTALLING, component)
            SafeHelperArchive.extract(archive, expanded, manifest)
            currentCoroutineContext().ensureActive()
            Files.move(expanded, destination, ATOMIC_MOVE)
            return destination.resolve(manifest.executable)
        } finally {
            withContext(NonCancellable) { deleteTree(stage) }
        }
    }

    private suspend fun download(manifest: HelperManifest, destination: Path, progress: (Long) -> Unit) {
        http.prepareGet(manifest.url) { header(HttpHeaders.AcceptEncoding, "identity") }.execute { response ->
            require(response.status == HttpStatusCode.OK) { "Helper download was refused" }
            response.headers[HttpHeaders.ContentLength]?.let { require(it.toLongOrNull() == manifest.archiveBytes) { "Helper response length changed" } }
            val channel = response.bodyAsChannel()
            Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(65_536)
                var completed = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count < 0) break
                    require(count.toLong() <= manifest.archiveBytes - completed) { "Helper response exceeds pinned bound" }
                    output.write(buffer, 0, count)
                    completed += count
                    progress(completed)
                }
                require(completed == manifest.archiveBytes) { "Helper response was truncated" }
            }
        }
    }

    private suspend fun installBundledFfmpeg(): Path {
        val name = if (platform.startsWith("windows")) "ffmpeg.exe" else "ffmpeg"
        val resource = "/dev/veyra/desktop/native/$platform/$name"
        val expected = javaClass.getResourceAsStream("$resource.sha256")?.use { it.readNBytes(129) }?.decodeToString()?.trim()
        if (expected == null || !DesktopHelperManifests.digestPattern.matches(expected))
            throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "bundled_ffmpeg_missing"))
        val directory = context.ownedPath(context.dataDirectory.resolve("runtime/ffmpeg/$expected"))
        DesktopProcessContext.privateDirectory(directory)
        val destination = directory.resolve(name)
        if (!Files.exists(destination, NOFOLLOW_LINKS)) {
            val input = javaClass.getResourceAsStream(resource)
                ?: throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "bundled_ffmpeg_missing"))
            val stage = Files.createTempFile(directory, ".ffmpeg-", ".part")
            try {
                input.use { source ->
                    Files.newOutputStream(stage, WRITE).use { output ->
                        val buffer = ByteArray(65_536)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= 67_108_864) { "Bundled FFmpeg exceeds the package bound" }
                            output.write(buffer, 0, count)
                        }
                        require(size > 0)
                    }
                }
                require(SafeHelperArchive.hashFile(stage) == expected) { "Bundled FFmpeg integrity check failed" }
                SafeHelperArchive.setPermissions(stage, executable = true)
                currentCoroutineContext().ensureActive()
                Files.move(stage, destination, ATOMIC_MOVE)
            } finally { Files.deleteIfExists(stage) }
        }
        require(Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.isExecutable(destination) && SafeHelperArchive.hashFile(destination) == expected) {
            "Bundled FFmpeg installation was changed"
        }
        return destination
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        Files.walk(root).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
    }
}
