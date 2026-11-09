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
import dev.veyra.desktop.runtime.CentralProofPhase
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopOptions
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.desktop.runtime.DesktopStartupJobs
import dev.veyra.desktop.runtime.DesktopVerificationOutcome
import dev.veyra.desktop.runtime.desktopResultWriteFailure
import dev.veyra.desktop.runtime.desktopVerificationFailure
import dev.veyra.desktop.runtime.finishDesktopVerification
import dev.veyra.desktop.runtime.parseDesktopOptions
import dev.veyra.desktop.runtime.writeDesktopVerificationResult
import dev.veyra.desktop.session.DesktopMediaSession
import dev.veyra.desktop.verification.CentralListeningProof
import dev.veyra.desktop.verification.CentralListeningProofFailure
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.playback.PlaybackCommand
import dev.veyra.ui.design.VeyraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.contains("--help")) {
        println("Veyra [--data-dir <directory>] [--silent-audio] [--self-test <audio-file> | --ui-smoke | --central-proof online|offline] [--verification-result <json-file>]")
        println("Central proof requires an explicit dedicated --data-dir. Result files must be directly inside <data-dir>/verification/results/ and end in .json; --self-test-result is an alias.")
        return
    }
    val options = try { parseDesktopOptions(args) } catch (_: Exception) { System.err.println("Invalid arguments; use --help."); exitProcess(2) }
    val context = try { DesktopProcessContext.acquire(options.dataDirectory) }
    catch (_: AlreadyRunningException) { System.err.println("Veyra is already running for this data directory."); exitProcess(73) }
    catch (_: Exception) { System.err.println("Veyra cannot use the selected data directory."); exitProcess(1) }
    try { DesktopNativeRuntime.initializeProcessPaths(context.dataDirectory) }
    catch (_: Throwable) { runCatching { context.close() }; System.err.println("Veyra cannot initialize private runtime storage."); exitProcess(1) }
    val lifetime = DesktopLifetime(context)
    val shutdown = Thread({
        try { runBlocking { lifetime.close() } }
        catch (_: Throwable) { System.err.println("Veyra runtime cleanup requires recovery.") }
    }, "Veyra-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    if (options.fixture != null || options.centralProof != null) {
        val code = try { runHeadlessVerification(options, context, lifetime) }
        finally { runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
        exitProcess(code)
    }
    var exitCode = 0
    val pendingReport = AtomicReference<String?>()
    var finalReport: DesktopVerificationOutcome? = null
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
                if (!lifetime.startupJobs.admit(DesktopStartupJobs.Kind.PLATFORM, coroutineContext[Job]!!)) return@LaunchedEffect
                try {
                    val services = DesktopPlatformServices.create(context, options.silentAudio,
                        onOwned = { lifetime.services = it })
                    val app = AppContainer(services, lifetime.scope)
                    lifetime.container = app
                    container = app
                    app.initialize()
                } catch (_: LinkageError) { startupFailed = true }
                catch (_: Exception) { startupFailed = true }
            }
            DisposableEffect(language) {
                val installed = if (SystemTray.isSupported() && !options.uiSmoke)
                    installTray(arabic, ::show, { lifetime.submitMediaCallback { container?.playback?.dispatch(PlaybackCommand.PlayPause) } }, ::quit)
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
                        if (!lifetime.startupJobs.admit(DesktopStartupJobs.Kind.MEDIA_SESSION, coroutineContext[Job]!!)) return@LaunchedEffect
                        val services = lifetime.services!!
                        DesktopMediaSession.create(context, window, lifetime.scope, app.playback.state,
                            command = { command -> lifetime.submitMediaCallback { app.playback.dispatch(command) } },
                            raise = ::show, quit = { EventQueue.invokeLater(::quit) },
                            setVolume = { value -> lifetime.submitMediaCallback { services.engine.setGain(value) } },
                            outputChanged = { lifetime.submitMediaCallback { services.engine.outputChanged() } },
                            onOwned = { lifetime.session = it })
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
                    // A rendered frame is not successful shutdown. Publish only after every
                    // native/source/Room owner and the process lock have actually closed.
                    pendingReport.set(report)
                    quit()
                }
            }
        }
    } catch (_: Throwable) {
        exitCode = 1
        System.err.println("Veyra could not complete its desktop session.")
    } finally {
        if (options.uiSmoke) {
            finalReport = runBlocking {
                finishDesktopVerification(pendingReport.get()?.takeIf { exitCode == 0 }
                    ?: desktopVerificationFailure(options, "ui_smoke_incomplete"), lifetime::close)
            }
            if (finalReport?.ownersClosed != true) exitCode = 1
        } else {
            try { runBlocking { lifetime.close() } }
            catch (_: Throwable) { exitCode = 1; System.err.println("Veyra runtime cleanup requires recovery.") }
        }
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
    finalReport?.let { if (publishVerification(context, options.result, it) != 0) exitCode = 1 }
    exitProcess(exitCode)
}

private fun runHeadlessVerification(options: DesktopOptions, context: DesktopProcessContext, lifetime: DesktopLifetime): Int {
    val candidate = try {
        runBlocking {
            // The JVM shutdown hook can run during headless initialization too.
            // Publish this job before any proof/native owner is allocated, so
            // lifetime.close retires admission and joins us before closing them.
            check(lifetime.startupJobs.admit(DesktopStartupJobs.Kind.PLATFORM, coroutineContext[Job]!!)) {
                "Desktop verification startup has already retired."
            }
            val fixture = options.fixture
            if (fixture != null) DesktopSelfTest.run(fixture, DesktopRuntimeConfig(context.dataDirectory, options.silentAudio))
            else {
                val proof = CentralListeningProof(options.silentAudio).also { lifetime.verification = it }
                val services = DesktopPlatformServices.create(context, options.silentAudio, proof.executionPolicy(),
                    onOwned = { lifetime.services = it })
                val app = AppContainer(services, lifetime.scope, gateway = proof.gateway(services.youtube)).also { lifetime.container = it }
                app.initialize() // Exactly one production container/store/engine, initialized once.
                when (options.centralProof) {
                    CentralProofPhase.ONLINE -> proof.runOnline(app)
                    CentralProofPhase.OFFLINE -> proof.runOffline(app)
                    null -> error("Verification phase is missing")
                }
            }
        }
    } catch (failure: CentralListeningProofFailure) {
        failure.report // Typed, bounded partial evidence; no native/source exception text.
    } catch (_: Throwable) {
        desktopVerificationFailure(options, "verification_operation_failed")
    }
    val outcome = runBlocking { finishDesktopVerification(candidate, lifetime::close) }
    return publishVerification(context, options.result, outcome)
}

private fun publishVerification(context: DesktopProcessContext, destination: Path?, outcome: DesktopVerificationOutcome): Int {
    var report = outcome.report
    var success = outcome.success
    if (!outcome.ownersClosed) System.err.println("Veyra runtime cleanup requires recovery.")
    try { writeDesktopVerificationResult(context, destination, report) }
    catch (_: Exception) {
        success = false
        report = desktopResultWriteFailure(report)
        System.err.println("Could not write the requested verification result.")
    }
    println(report) // Never print a success before required cleanup and requested-file publication.
    if (System.out.checkError()) success = false
    return if (success) 0 else 1
}

internal class DesktopLifetime(
    private val context: DesktopProcessContext,
    callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val reportCallbackFailure: (String) -> Unit = { System.err.println(it) },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbackJob = SupervisorJob(scope.coroutineContext[Job])
    private val callbackScope = CoroutineScope(callbackJob + callbackDispatcher)
    private val callbackAdmission = Any()
    @Volatile var services: DesktopPlatformServices? = null
    @Volatile var container: AppContainer? = null
    @Volatile var session: DesktopMediaSession? = null
    @Volatile var verification: AutoCloseable? = null
    val startupJobs = DesktopStartupJobs()
    private val closing = AtomicBoolean()
    // callbackAdmission selects one owner per close attempt. Concurrent callers share that
    // attempt; only a later explicit call may retry an unacknowledged application drain.
    private var closeAttempt: CompletableDeferred<Throwable?>? = null
    // Written only by the selected owner, before publishing its completed attempt.
    private var retirementAttempted = false
    private var hostCloseAttempted = false
    private var closeFailure: Throwable? = null
    internal val retainedForCleanup: Boolean get() = DesktopCleanupOwners.contains(this)

    /** Native/tray callbacks only enqueue here; they never synchronously wait for work or close.
     * Quit deliberately uses the host scope instead, so it cannot join its own callback job. */
    fun submitMediaCallback(action: suspend () -> Unit): Boolean = synchronized(callbackAdmission) {
        if (closing.get() || !callbackJob.isActive) return@synchronized false
        // DEFAULT start attaches the child before releasing admission. If the parent is
        // cancelled concurrently, the queued child cannot execute its callback body.
        callbackScope.launch {
            if (closing.get()) return@launch // Drop an admitted but not-yet-started callback.
            try { action() }
            catch (_: CancellationException) { /* Normal callback retirement; no raw cause is logged. */ }
            catch (_: Throwable) {
                // Source/native errors can contain private URLs. Neither the diagnostic nor
                // a failing diagnostic sink may forward an arbitrary Throwable to the handler.
                try { reportCallbackFailure("Veyra could not complete a media-control action.") }
                catch (_: Throwable) { /* A diagnostic must not bypass callback cleanup. */ }
            }
        }
        true
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        // Retire callback admission before the first dispatcher switch. The same gate covers
        // child attachment, so cancelAndJoin below cannot miss a successfully admitted child.
        val (completion, ownsClose) = synchronized(callbackAdmission) {
            closing.set(true)
            val previous = closeAttempt
            if (previous != null && (!previous.isCompleted || hostCloseAttempted)) previous to false
            else {
                // The app's own pin cannot keep this host or its process lock alive. Publish
                // our strong owner before the first fallible/suspending cleanup operation.
                DesktopCleanupOwners.retain(this@DesktopLifetime)
                CompletableDeferred<Throwable?>().also { closeAttempt = it } to true
            }
        }
        if (ownsClose) {
            try {
                withContext(Dispatchers.IO) { finishCloseAttempt() }
            } catch (error: Throwable) {
                if (closeFailure == null) closeFailure = error
            } finally {
                synchronized(callbackAdmission) { completion.complete(closeFailure) }
            }
        }
        // Store the outcome as data so every caller observes the same sticky failure, even
        // if its own Job was cancelled before admission or while another attempt drained.
        completion.await()?.let { throw it }
    }

    private suspend fun finishCloseAttempt() {
        suspend fun release(action: suspend () -> Unit) {
            try { action() } catch (error: Throwable) { if (closeFailure == null) closeFailure = error }
        }
        if (!retirementAttempted) {
            retirementAttempted = true
            // Retire both admissions before awaiting either job. A late Compose
            // effect must not allocate an owner after this close took its snapshot.
            for (job in startupJobs.retire()) release { job.cancel(); job.join() }
            // Includes suspended callbacks and their non-cancellable finalizers. Keep
            // session/engine/source/store/context owners alive until every callback ends.
            release { callbackJob.cancelAndJoin() }
            release { session?.close() }
        }
        val app = container
        if (app != null) {
            release { app.close() }
            if (!app.dependencyReleaseSafeAfterClose || app.retainedForCleanup) {
                // An exception (or its absence) is not a dependency handoff. Retained cleanup
                // may still use Room, runtime clients, the parent scope and this process lock.
                // No automatic retry loop: the next explicit close shares/retries only the app.
                if (closeFailure == null) closeFailure = DesktopCleanupNotAcknowledged()
                return
            }
        }
        // No app was published, or its positive receipt certifies consumption. Keep the
        // existing one-shot policy for independent/partial-startup owners, including failures.
        // In particular a verification-only failure does not retain an unrelated process lock.
        hostCloseAttempted = true
        try {
            release { services?.closeAcquiredResources() }
            release { verification?.close() }
        } finally {
            release { context.close() }
            release { scope.cancel() }
            DesktopCleanupOwners.release(this)
        }
    }
}

/** Strong process pin only; no I/O, timer, finalizer or implicit cleanup retry. */
private object DesktopCleanupOwners {
    private val retained = ConcurrentHashMap.newKeySet<DesktopLifetime>()
    fun retain(owner: DesktopLifetime) { retained.add(owner) }
    fun release(owner: DesktopLifetime) { retained.remove(owner) }
    fun contains(owner: DesktopLifetime): Boolean = owner in retained
}

private class DesktopCleanupNotAcknowledged(
    val reason: String = "desktop_application_cleanup_not_acknowledged",
) : IllegalStateException(reason)

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
