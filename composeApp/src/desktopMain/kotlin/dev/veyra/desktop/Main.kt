package dev.veyra.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.veyra.app.AppContainer
import dev.veyra.app.AppInitialization
import dev.veyra.app.VeyraApp
import dev.veyra.desktop.audio.DesktopSelfTest
import dev.veyra.desktop.runtime.AlreadyRunningException
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.desktop.session.DesktopMediaSession
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.ui.design.VeyraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private data class Options(
    val dataDirectory: Path = DesktopProcessContext.defaultDirectory(),
    val fixture: Path? = null,
    val result: Path? = null,
    val silentAudio: Boolean = false,
    val uiSmoke: Boolean = false,
)

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        println("Veyra [--data-dir <directory>] [--self-test <audio-file> --silent-audio] [--self-test-result <owned-json-file>] [--ui-smoke]")
        return
    }
    val options = try { parseOptions(args) } catch (_: Exception) { System.err.println("Invalid arguments; use --help."); exitProcess(2) }
    val context = try { DesktopProcessContext.acquire(options.dataDirectory) }
    catch (_: AlreadyRunningException) { System.err.println("Veyra is already running for this data directory."); exitProcess(73) }
    catch (_: Exception) { System.err.println("Veyra cannot use the selected data directory."); exitProcess(1) }
    try { DesktopNativeRuntime.initializeProcessPaths(context.dataDirectory) }
    catch (_: Exception) { context.close(); System.err.println("Veyra cannot initialize private runtime storage."); exitProcess(1) }
    if (options.fixture != null) {
        var success = false
        val report = try {
            runBlocking { DesktopSelfTest.run(options.fixture, DesktopRuntimeConfig(context.dataDirectory, options.silentAudio)) }.also { success = true }
        } catch (error: Throwable) {
            "{\"success\":false,\"silentAudio\":${options.silentAudio},\"error\":\"${error.javaClass.simpleName}\"}"
        }
        try { writeResult(context, options.result, report) } catch (_: Exception) { success = false; System.err.println("Could not write the requested self-test result.") }
        context.close()
        exitProcess(if (success) 0 else 1)
    }
    val lifetime = DesktopLifetime(context)
    val shutdown = Thread({ runBlocking { lifetime.close() } }, "Veyra-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    var exitCode = 0
    try {
        application {
            var visible by remember { mutableStateOf(true) }
            var confirmQuit by remember { mutableStateOf(false) }
            var closing by remember { mutableStateOf(false) }
            var startupFailed by remember { mutableStateOf(false) }
            var container by remember { mutableStateOf<AppContainer?>(null) }
            var tray by remember { mutableStateOf<TrayIcon?>(null) }
            val settings = container?.store?.state?.collectAsState()?.value?.settings
            val language = container?.effectiveLanguage(settings?.language ?: LanguagePreference.SYSTEM)
                ?: if (Locale.getDefault().language == "ar") LanguagePreference.ARABIC else LanguagePreference.ENGLISH
            val arabic = language == LanguagePreference.ARABIC
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings?.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> systemDark }
            fun quit() {
                if (closing) return
                closing = true
                lifetime.scope.launch {
                    withContext(NonCancellable) {
                        try { lifetime.close() }
                        catch (_: Throwable) { exitCode = 1; System.err.println("Veyra could not completely release its runtime resources.") }
                        finally { withContext(Dispatchers.Main) { exitApplication() } }
                    }
                }
            }
            fun show() { EventQueue.invokeLater { visible = true } }
            LaunchedEffect(Unit) {
                lifetime.bootstrapJob = coroutineContext[Job]
                try {
                    val services = DesktopPlatformServices.create(context, options.silentAudio)
                    lifetime.services = services
                    val app = AppContainer(services, lifetime.scope)
                    lifetime.container = app
                    container = app
                    app.initialize()
                } catch (_: LinkageError) { startupFailed = true }
                catch (_: Exception) { startupFailed = true }
            }
            DisposableEffect(language) {
                val installed = if (SystemTray.isSupported() && !options.uiSmoke)
                    installTray(arabic, ::show, { lifetime.scope.launch { container?.playback?.dispatch(PlaybackCommand.PlayPause) } }, ::quit)
                else null
                tray = installed
                onDispose { installed?.let { SystemTray.getSystemTray().remove(it) } }
            }
            Window(
                onCloseRequest = { if (tray != null && !options.uiSmoke) visible = false else confirmQuit = true },
                visible = visible,
                title = if (arabic) "ڤيرا · Veyra" else "Veyra",
                state = rememberWindowState(width = 1180.dp, height = 820.dp),
            ) {
                MenuBar { Menu("Veyra") { Item(if (arabic) "إنهاء" else "Quit", onClick = ::quit) } }
                val app = container
                LaunchedEffect(app) {
                    if (app != null) {
                        lifetime.sessionJob = coroutineContext[Job]
                        val services = lifetime.services!!
                        lifetime.session = DesktopMediaSession.create(context, window, lifetime.scope, app.playback.state,
                            command = { command -> lifetime.scope.launch { app.playback.dispatch(command) } },
                            raise = ::show, quit = { EventQueue.invokeLater(::quit) },
                            setVolume = { value -> lifetime.scope.launch { services.engine.setGain(value) } },
                            outputChanged = { lifetime.scope.launch { services.engine.outputChanged() } })
                    }
                }
                if (app != null) VeyraApp(app, Modifier.fillMaxSize())
                else VeyraTheme(dark, language) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (startupFailed) {
                                Text(if (arabic) "تعذّر بدء ڤيرا. تحقّق من أذونات التخزين وحزمة التشغيل." else "Veyra could not start. Check storage permissions and the bundled runtime.")
                                Button(onClick = ::quit) { Text(if (arabic) "إغلاق" else "Close") }
                            } else {
                                CircularProgressIndicator()
                                Text(if (arabic) "جارٍ تجهيز ڤيرا…" else "Preparing Veyra…")
                            }
                        }
                    }
                }
                if (confirmQuit) VeyraTheme(dark, language) {
                    AlertDialog(
                        onDismissRequest = { confirmQuit = false },
                        title = { Text(if (arabic) "إنهاء ڤيرا؟" else "Quit Veyra?") },
                        text = { Text(if (arabic) "سيتوقف الصوت وتُحفظ التنزيلات لاستئنافها عند فتح التطبيق." else "Audio stops and downloads are checkpointed for the next launch.") },
                        confirmButton = { TextButton(onClick = ::quit) { Text(if (arabic) "إنهاء" else "Quit") } },
                        dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text(if (arabic) "إلغاء" else "Cancel") } },
                    )
                }
                LaunchedEffect(options.uiSmoke, app, startupFailed) {
                    if (!options.uiSmoke || (app == null && !startupFailed)) return@LaunchedEffect
                    val ready = if (startupFailed) false else withTimeoutOrNull(45_000) {
                        app!!.initialization.first { it !is AppInitialization.Loading } is AppInitialization.Ready
                    } ?: false
                    withFrameNanos { }; withFrameNanos { }
                    delay(500)
                    val report = "{\"success\":$ready,\"uiSmoke\":true,\"silentAudio\":${options.silentAudio},\"physicalAudioVerified\":false}"
                    exitCode = if (ready) 0 else 1
                    try { withContext(Dispatchers.IO) { writeResult(context, options.result, report) } }
                    catch (_: Exception) { exitCode = 1 }
                    finally { quit() }
                }
            }
        }
    } finally {
        try { runBlocking { lifetime.close() } }
        catch (_: Throwable) { exitCode = 1; System.err.println("Veyra runtime cleanup requires recovery.") }
        Runtime.getRuntime().removeShutdownHook(shutdown)
    }
    exitProcess(exitCode)
}

private class DesktopLifetime(private val context: DesktopProcessContext) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile var services: DesktopPlatformServices? = null
    @Volatile var container: AppContainer? = null
    @Volatile var session: DesktopMediaSession? = null
    @Volatile var bootstrapJob: Job? = null
    @Volatile var sessionJob: Job? = null
    private val closing = AtomicBoolean()
    private val closed = CompletableDeferred<Unit>()
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (!closing.compareAndSet(false, true)) { closed.await(); return@withContext }
        var failure: Throwable? = null
        suspend fun release(action: suspend () -> Unit) {
            try { action() } catch (error: Throwable) { if (failure == null) failure = error }
        }
        try {
            release { bootstrapJob?.let { it.cancel(); it.join() } }
            release { sessionJob?.let { it.cancel(); it.join() } }
            release { session?.close() }
            release { container?.close() }
            // Also protect partial initialization; every owner close is idempotent. Attempt all
            // cleanup even if an earlier owner failed, retaining the first failure for every waiter.
            services?.let { service ->
                release { service.closeMediaRuntime() }
                release { service.engine.close() }
                release { service.youtube.close() }
                release { service.store.close() }
            }
        } finally {
            release { context.close() }
            val error = failure
            if (error == null) closed.complete(Unit) else closed.completeExceptionally(error)
            scope.cancel()
        }
        failure?.let { throw it }
    }
}

private fun parseOptions(args: Array<String>): Options {
    var value = Options()
    var index = 0
    while (index < args.size) {
        fun path(): Path { index++; require(index < args.size); return Path.of(args[index]).toAbsolutePath().normalize() }
        value = when (args[index]) {
            "--data-dir" -> value.copy(dataDirectory = path())
            "--self-test" -> value.copy(fixture = path())
            "--self-test-result" -> value.copy(result = path())
            "--silent-audio" -> value.copy(silentAudio = true)
            "--ui-smoke" -> value.copy(uiSmoke = true)
            else -> error("Unknown option")
        }
        index++
    }
    require(value.fixture == null || !value.uiSmoke)
    require(value.result == null || value.fixture != null || value.uiSmoke)
    return value
}

private fun writeResult(context: DesktopProcessContext, result: Path?, report: String) {
    println(report)
    if (result == null) return
    val destination = context.ownedPath(result)
    DesktopProcessContext.privateDirectory(destination.parent)
    val stage = Files.createTempFile(destination.parent, ".result-", ".json")
    try { Files.writeString(stage, report + "\n"); Files.move(stage, destination, ATOMIC_MOVE, REPLACE_EXISTING) }
    finally { Files.deleteIfExists(stage) }
}

private fun installTray(arabic: Boolean, show: () -> Unit, playPause: () -> Unit, quit: () -> Unit): TrayIcon? = runCatching {
    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        color = Color(0x00696A); fillRoundRect(0, 0, 32, 32, 10, 10)
        color = Color.WHITE; stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val wave = Path2D.Double().apply { moveTo(6.0, 19.0); curveTo(12.0, 5.0, 17.0, 30.0, 26.0, 12.0) }
        draw(wave); dispose()
    }
    val menu = PopupMenu().apply {
        add(MenuItem(if (arabic) "فتح ڤيرا" else "Show Veyra").apply { addActionListener { show() } })
        add(MenuItem(if (arabic) "تشغيل / إيقاف مؤقت" else "Play / Pause").apply { addActionListener { playPause() } })
        addSeparator()
        add(MenuItem(if (arabic) "إنهاء" else "Quit").apply { addActionListener { quit() } })
    }
    TrayIcon(image, "Veyra", menu).apply { isImageAutoSize = true; addActionListener { show() }; SystemTray.getSystemTray().add(this) }
}.getOrNull()
