package dev.veyra.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.data.StoreStatus
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.FailureCode
import dev.veyra.model.LanguagePreference
import dev.veyra.model.ThemeMode
import dev.veyra.model.VeyraException
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Actual root/controller/Room, with authored runtime states and a no-network fixture engine.
 * Recovery damages only an owned, newly seeded Room database; there is no fake persistence.
 * The one-document-error case uses the real legal screen's reader seam, not bundled-file edits.
 * These tests establish neither real runtime installation nor native/physical playback.
 * Run only in the lead's build slot through the approved repository-owned display/cache guard.
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryAndRuntimeUiTest {
    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)

    @Test fun requiredToolsStayPassiveOutsideSettingsAndPrepareIsDisabledOffline() = appCase(
        initial = CoreShellTestFixture.snapshot(
            settings = AppSettings(language = LanguagePreference.ENGLISH, theme = ThemeMode.LIGHT, offlineOnly = true),
            navigation = AppNavigation(destination = AppDestination.LIBRARY),
        ),
        runtime = MediaRuntimeStatus(MediaRuntimePhase.REQUIRED),
    ) { app ->
        ui.onNodeWithTag("media-runtime-status").assertDoesNotExist()
        assertEquals(0, app.runtimePrepareCalls.get(), "Opening an offline library never installs tools")

        ui.onNodeWithContentDescription(english.text(TextKey.Settings)).performClick()
        await { app.container.navigation.value.destination == AppDestination.SETTINGS }
        control("media-runtime-open")
        ui.onNodeWithTag("media-runtime-open").performClick()
        control("media-runtime-prepare").assertIsNotEnabled()
            .assertTextEquals(english.text(TextKey.PlaybackToolsPrepare)).performClick()
        readable("media-runtime-details", english.text(TextKey.PlaybackToolsRequired))
        readable("media-runtime-details", english.text(TextKey.PlaybackToolsPrivacy))
        ui.waitForIdle()
        assertEquals(0, app.runtimePrepareCalls.get(), "A disabled offline action must not reach the platform")
        closeRuntime(english).performClick()
        ui.onNodeWithTag("media-runtime-details").assertDoesNotExist()
        assertEquals(0, app.runtimePrepareCalls.get(), "Opening and closing explanatory details is passive")
    }

    @Test fun explicitPrepareShowsControlledProgressAndClosingDetailsDoesNotRestartSetup() = appCase(
        initial = settingsSnapshot(), runtime = MediaRuntimeStatus(MediaRuntimePhase.REQUIRED), width = 960, height = 720,
    ) { app ->
        val finish = CompletableDeferred<Unit>()
        app.prepareRuntime = {
            app.runtime.value = MediaRuntimeStatus(MediaRuntimePhase.DOWNLOADING,
                component = MediaRuntimeComponent.NODE, completedBytes = 16, totalBytes = 64)
            finish.await()
            app.runtime.value = MediaRuntimeStatus(MediaRuntimePhase.READY)
        }
        ui.onNodeWithTag("media-runtime-open").performClick()
        assertEquals(0, app.runtimePrepareCalls.get())
        ui.onNodeWithTag("media-runtime-prepare").assertIsEnabled().performClick()
        await { app.runtimePrepareCalls.get() == 1 && app.runtime.value.phase == MediaRuntimePhase.DOWNLOADING }
        ui.onNodeWithTag("media-runtime-prepare").assertDoesNotExist()
        progress(0.25f)
        readable("media-runtime-details", english.format(TextKey.PlaybackToolsDownloadProgress,
            english.byteSize(16), english.byteSize(64)))
        closeRuntime(english).performClick()
        ui.onNodeWithTag("media-runtime-open").performClick()
        assertEquals(1, app.runtimePrepareCalls.get(), "Reopening details must not create another preparation")
        progress(0.25f)
        finish.complete(Unit)
        await { app.runtime.value.phase == MediaRuntimePhase.READY }
        ui.onNodeWithTag("media-runtime-status").assertDoesNotExist()
        ui.onNodeWithTag("media-runtime-details").assertDoesNotExist()
        assertEquals(1, app.runtimePrepareCalls.get())
    }

    @Test fun arabicDownloadDetailsAndUnknownProgressFitAnActual320dpWindowAt200Percent() = appCase(
        initial = settingsSnapshot(arabic = true),
        runtime = MediaRuntimeStatus(MediaRuntimePhase.DOWNLOADING,
            component = MediaRuntimeComponent.CHROMIUM, completedBytes = 3_145_728, totalBytes = 8_388_608),
    ) { app ->
        control("media-runtime-open")
        progress(0.375f)
        capture("fixture-runtime-ar-320x760-font200-progress")
        ui.onNodeWithTag("media-runtime-open").performClick()
        visible(ui.onNodeWithTag("media-runtime-details"), "runtime dialog")
        readable("media-runtime-details", arabic.format(TextKey.PlaybackToolsComponent,
            arabic.text(TextKey.PlaybackToolChromium)))
        readable("media-runtime-details", arabic.format(TextKey.PlaybackToolsDownloadProgress,
            arabic.byteSize(3_145_728), arabic.byteSize(8_388_608)))
        readable("media-runtime-details", arabic.text(TextKey.PlaybackToolsPrivacy))
        closeRuntime(arabic)
        capture("fixture-runtime-ar-320x760-font200-details", "media-runtime-details")

        app.runtime.value = app.runtime.value.copy(completedBytes = -64, totalBytes = 256)
        ui.waitForIdle()
        progress(0f)
        readable("media-runtime-details", arabic.format(TextKey.PlaybackToolsDownloadProgress,
            arabic.byteSize(0), arabic.byteSize(256)))
        app.runtime.value = app.runtime.value.copy(completedBytes = 512, totalBytes = 256)
        ui.waitForIdle()
        progress(1f)
        app.runtime.value = app.runtime.value.copy(completedBytes = 512, totalBytes = null)
        ui.waitForIdle()
        assertEquals(ProgressBarRangeInfo.Indeterminate,
            ui.onNodeWithTag("media-runtime-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        readable("media-runtime-details", arabic.format(TextKey.PlaybackToolsDownloadedBytes, arabic.byteSize(512)))
        closeRuntime(arabic).performClick()
        assertEquals(0, app.runtimePrepareCalls.get(), "Progress is observed, not an installation trigger")
    }

    @Test fun arabicRuntimeErrorUsesTypedCopyAndKeepsOfflineRetryDisabledAt200Percent() = appCase(
        initial = settingsSnapshot(arabic = true, offline = true),
        runtime = MediaRuntimeStatus(MediaRuntimePhase.ERROR, component = MediaRuntimeComponent.FFMPEG,
            failure = AppFailure(FailureCode.NETWORK_UNAVAILABLE, DIAGNOSTIC_SENTINEL)),
    ) { app ->
        control("media-runtime-open").performClick()
        visible(ui.onNodeWithTag("media-runtime-details"), "runtime error dialog")
        readable("media-runtime-details", arabic.errorFor(AppFailure(FailureCode.NETWORK_UNAVAILABLE)))
        readable("media-runtime-details", arabic.format(TextKey.PlaybackToolsComponent,
            arabic.text(TextKey.PlaybackToolFfmpeg)))
        control("media-runtime-prepare").assertIsNotEnabled().assertTextEquals(arabic.text(TextKey.Retry))
        closeRuntime(arabic)
        ui.onAllNodesWithText(DIAGNOSTIC_SENTINEL, substring = true, useUnmergedTree = true).assertCountEquals(0)
        capture("fixture-runtime-ar-320x760-font200-typed-error", "media-runtime-details")
        ui.onNodeWithTag("media-runtime-prepare").performClick()
        ui.waitForIdle()
        assertEquals(0, app.runtimePrepareCalls.get())
    }

    @Test fun damagedRoomRequiresExplicitConfirmationAndCancelNeverRestoresAtArabic200Percent() {
        val initial = CoreShellTestFixture.snapshot(settings = AppSettings(
            language = LanguagePreference.ARABIC, theme = ThemeMode.LIGHT, offlineOnly = true))
        appCase(platformFontScale = 2f, createFixture = {
            failedRoom(initial, LanguagePreference.ARABIC) { library -> primary(library).writeBytes(DAMAGED_PRIMARY) }
        }) { app ->
            val library = File(app.directory, "library")
            assertUnrestored(app, library)
            ui.onNodeWithTag("recovery-restore").performScrollTo()
            control("recovery-restore").performClick()
            visible(ui.onNodeWithTag("recovery-confirm-dialog"), "recovery confirmation")
            readable("recovery-confirm-dialog", arabic.text(TextKey.RecoveryBackupPrompt))
            control("recovery-confirm")
            control("recovery-cancel")
            assertUnrestored(app, library)
            capture("fixture-recovery-ar-320x760-font200-confirm", "recovery-confirm-dialog")
            ui.onNodeWithTag("recovery-cancel").performClick()
            ui.onNodeWithTag("recovery-confirm-dialog").assertDoesNotExist()
            assertUnrestored(app, library)

            ui.onNodeWithTag("recovery-restore").performScrollTo().performClick()
            ui.onNodeWithTag("recovery-confirm").performClick()
            await { app.container.initialization.value is AppInitialization.Ready }
            assertEquals(StoreStatus.Ready(recoveredFromBackup = true), app.store.status.value)
            val restored = app.store.state.value
            assertEquals(initial.media, restored.media)
            assertEquals(initial.favoriteIds, restored.favoriteIds)
            assertEquals(initial.playlists, restored.playlists, "Duplicate playlist occurrences survive recovery")
            assertEquals(initial.queues, restored.queues)
            assertEquals(initial.settings, restored.settings)
            val kept = quarantines(library)
            assertEquals(1, kept.size, "The damaged input is preserved, never reset or silently replaced")
            assertContentEquals(DAMAGED_PRIMARY, kept.single().readBytes())
            assertFalse(app.container.playback.state.value.desiredPlaying, "Backup recovery restores paused")
            ui.onNodeWithTag("recovery-restore").assertDoesNotExist()
            assertEquals(0, app.runtimePrepareCalls.get())
        }
    }

    @Test fun missingBackupAfterConfirmationStaysFailedAndKeepsTheDamagedPrimary() = appCase(
        createFixture = {
            failedRoom { library ->
                primary(library).writeBytes(DAMAGED_PRIMARY)
                check(File(library, "veyra.backup.db").delete())
            }
        },
    ) { app ->
        val library = File(app.directory, "library")
        val initialFailure = assertIs<StoreStatus.Failed>(app.store.status.value).failure
        ui.onNodeWithTag("recovery-restore").performScrollTo().performClick()
        assertUnrestored(app, library)
        ui.onNodeWithTag("recovery-confirm").performClick()
        // The missing-backup failure differs from the invalid-primary failure, proving this is
        // the completed explicit attempt, not the still-queued initial Failed state.
        await {
            app.container.initialization.value is AppInitialization.Failed &&
                (app.store.status.value as? StoreStatus.Failed)?.failure?.let { it != initialFailure } == true
        }
        assertUnrestored(app, library)
        ui.onNodeWithTag("recovery-restore").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText(english.errorFor(AppFailure(FailureCode.RECOVERY_REQUIRED))).assertExists()
        ui.onAllNodesWithText("No verified backup is available", substring = true, useUnmergedTree = true).assertCountEquals(0)
        assertFalse(File(library, "veyra.recovering").exists())
    }

    @Test fun futureRoomSchemaNeverOffersOrdinaryBackupRestoreOrDowngrade() = appCase(
        createFixture = {
            failedRoom(expected = FailureCode.FUTURE_SCHEMA) { library ->
                val wal = File(library, "veyra.db-wal")
                check(!wal.exists() || wal.length() == 0L) { "The authored database must be cleanly closed first" }
                // SQLite's documented big-endian user_version field. Only this owned fixture is
                // edited; no SQL driver/private shared-test API or existing database is borrowed.
                RandomAccessFile(primary(library), "rw").use { database ->
                    check(database.length() >= 100)
                    database.seek(60)
                    database.writeInt(999)
                }
            }
        },
    ) { app ->
        val library = File(app.directory, "library")
        val before = primary(library).readBytes()
        ui.onNodeWithTag("recovery-restore").assertDoesNotExist()
        ui.onNodeWithTag("recovery-confirm-dialog").assertDoesNotExist()
        ui.onNodeWithText(english.errorFor(AppFailure(FailureCode.FUTURE_SCHEMA))).assertIsDisplayed()
        ui.waitForIdle()
        assertContentEquals(before, primary(library).readBytes())
        assertTrue(quarantines(library).isEmpty())
        assertEquals(FailureCode.FUTURE_SCHEMA, assertIs<AppInitialization.Failed>(app.container.initialization.value).failure.code)
    }

    @Test fun actualOfflineRootOpensEveryBundledLegalDocumentWithoutBrowserOrNetwork() = appCase(
        initial = settingsSnapshot(offline = true), width = 960, height = 720,
    ) { app ->
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-licenses"))
        ui.onNodeWithTag("settings-licenses").performClick()
        await { app.container.navigation.value.detail?.kind == DetailKind.LICENSES }
        await { ui.onAllNodes(hasTestTag("legal-document-NOTICE.md")).fetchSemanticsNodes().isNotEmpty() }
        val excerpts = listOf("Software and source notices", "GNU GENERAL PUBLIC LICENSE", "MIT License",
            "Licensing and source provenance", "does NOT certify a binary release", "SIL Open Font License, Version 1.1")
        LEGAL_NAMES.forEachIndexed { index, name ->
            legalDocument(index, name)
            ui.onNodeWithTag("legal-unavailable-$name").assertDoesNotExist()
            ui.onNode(hasText(excerpts[index], substring = true) and hasAnyAncestor(hasTestTag("legal-document-$name")),
                useUnmergedTree = true).assertExists()
        }
        assertEquals(0, app.runtimePrepareCalls.get(), "Reading licenses is not online setup")
        capture("fixture-legal-bundled-offline-font-notice")
    }

    @Test fun oneUnavailableLegalDocumentDoesNotHideOtherNoticesOrTheFontLicense() = runDesktopComposeUiTest(
        width = 320, height = 760, testTimeout = 60.seconds,
    ) {
        val requested = CopyOnWriteArrayList<String>()
        val reader: suspend (String) -> String = { path ->
            requested += path
            if (path == "files/legal/LICENSE") throw IOException(DIAGNOSTIC_SENTINEL)
            authoredNotice(path.substringAfterLast('/'))
        }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                VeyraTheme(darkTheme = true, language = LanguagePreference.ARABIC) { LicensesScreen(reader) }
            }
        }
        val screen = ScreenCase(this, 320, 760)
        screen.viewport("licenses-screen")
        screen.await { requested.size == LEGAL_NAMES.size }
        onNodeWithText(arabic.text(TextKey.LegalOriginalTerms)).assertIsDisplayed()
        LEGAL_NAMES.forEachIndexed { index, name ->
            screen.legalDocument(index, name)
            if (name == "LICENSE") {
                onNodeWithTag("legal-unavailable-$name").assertIsDisplayed()
                onNode(hasText(arabic.text(TextKey.InformationUnavailable)) and hasAnyAncestor(hasTestTag("legal-document-$name")),
                    useUnmergedTree = true).assertIsDisplayed()
            } else {
                onNodeWithTag("legal-unavailable-$name").assertDoesNotExist()
                screen.readable("legal-document-$name", authoredNotice(name), scroll = false)
            }
        }
        assertEquals(LEGAL_NAMES.dropLast(1).map { "files/legal/$it" } + "files/licenses/${LEGAL_NAMES.last()}", requested.toList())
        onAllNodesWithText(DIAGNOSTIC_SENTINEL, substring = true, useUnmergedTree = true).assertCountEquals(0)
        screen.capture("fixture-legal-ar-320x760-font200-one-missing", "licenses-screen")
    }

    private fun settingsSnapshot(arabic: Boolean = false, offline: Boolean = false): AppSnapshot = CoreShellTestFixture.snapshot(
        settings = AppSettings(language = if (arabic) LanguagePreference.ARABIC else LanguagePreference.ENGLISH,
            theme = if (arabic) ThemeMode.DARK else ThemeMode.LIGHT, textScale = if (arabic) 2f else 1f, offlineOnly = offline),
        navigation = AppNavigation(destination = AppDestination.SETTINGS),
    )

    private fun appCase(
        initial: AppSnapshot = CoreShellTestFixture.snapshot(),
        runtime: MediaRuntimeStatus = MediaRuntimeStatus(),
        width: Int = 320,
        height: Int = 760,
        platformFontScale: Float = 1f,
        createFixture: suspend () -> CoreShellTestFixture = { CoreShellTestFixture.create(initial) },
        block: suspend ScreenCase.(CoreShellTestFixture) -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height, testTimeout = 60.seconds) {
        val app = withContext(Dispatchers.IO) { withTimeout(20_000L) { createFixture() } }
        app.runtime.value = runtime
        var mounted by mutableStateOf(true)
        try {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, platformFontScale)) {
                    Box(Modifier.fillMaxSize()) { if (mounted) VeyraApp(app.container) }
                }
            }
            val screen = ScreenCase(this, width, height)
            screen.viewport("veyra-app")
            screen.block(app)
        } finally {
            try { runOnIdle { mounted = false }; waitForIdle() }
            finally { app.close() }
            assertEquals(0, app.unexpectedNetworkCalls.get(), "These authored fixtures must not open catalog/HTTP/DNS")
            assertEquals(0, app.gateway.downloadCalls.get(), "Runtime/legal/recovery UI must not acquire media bytes")
            assertTrue(app.gateway.queries.isEmpty() && app.gateway.resolutions.isEmpty(), "No source execution is expected")
            assertTrue(app.engine.loads.isEmpty(), "Recovery and runtime inspection must not start native playback")
            assertTrue(app.externalPages.isEmpty(), "Local legal/recovery/runtime details must not open a browser")
        }
    }

    private suspend fun failedRoom(
        initial: AppSnapshot = CoreShellTestFixture.snapshot(),
        systemLanguage: LanguagePreference = LanguagePreference.ENGLISH,
        expected: FailureCode = FailureCode.RECOVERY_REQUIRED,
        damage: (File) -> Unit,
    ): CoreShellTestFixture {
        val parent = coreShellOwnedDirectory("composeApp/build/test-work/design-recovery")
        val directory = Files.createTempDirectory(parent.toPath(), "fixture-").toFile()
        val library = File(directory, "library")
        val seed = createPlatformLibraryStore(library.path)
        try { seed.update { initial }.getOrThrow(); seed.backup().getOrThrow() }
        finally { seed.close() }
        damage(library)
        val store = createPlatformLibraryStore(library.path)
        val app = CoreShellTestFixture.attachUninitialized(directory, store, systemLanguage)
        try {
            try { app.container.initialize(); error("The authored damaged/future database must refuse startup") }
            catch (failure: VeyraException) { assertEquals(expected, failure.failure.code) }
            assertEquals(expected, assertIs<AppInitialization.Failed>(app.container.initialization.value).failure.code)
            assertEquals(expected, assertIs<StoreStatus.Failed>(store.status.value).failure.code)
            return app
        } catch (failure: Throwable) {
            app.close()
            throw failure
        }
    }

    private fun assertUnrestored(app: CoreShellTestFixture, library: File) {
        assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<AppInitialization.Failed>(app.container.initialization.value).failure.code)
        assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<StoreStatus.Failed>(app.store.status.value).failure.code)
        assertContentEquals(DAMAGED_PRIMARY, primary(library).readBytes())
        assertTrue(quarantines(library).isEmpty(), "No backup restore or quarantine is permitted before confirmation")
    }

    private fun primary(library: File) = File(library, "veyra.db")
    private fun quarantines(library: File): List<File> = library.listFiles().orEmpty()
        .filter { it.name.startsWith("veyra.corrupt-") && it.name.endsWith(".db") }

    private class ScreenCase(val ui: DesktopComposeUiTest, private val width: Int, private val height: Int) {
        fun await(condition: () -> Boolean) { ui.waitUntil(timeoutMillis = 10_000L, condition = condition); ui.waitForIdle() }

        fun viewport(tag: String) {
            ui.waitForIdle()
            val actual = ui.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals(width.dp, actual.width, "The scene, not just an inner Box, has the claimed width")
            assertEquals(height.dp, actual.height, "The scene has the claimed height")
        }

        fun control(tag: String): SemanticsNodeInteraction = visible(ui.onNodeWithTag(tag), tag, control = true)

        fun visible(node: SemanticsNodeInteraction, label: String, control: Boolean = false): SemanticsNodeInteraction {
            node.assertIsDisplayed()
            val actual = node.getUnclippedBoundsInRoot()
            val clipped = node.fetchSemanticsNode().boundsInRoot
            assertTrue(actual.left.value >= -1f && actual.top.value >= -1f &&
                actual.right.value <= width + 1f && actual.bottom.value <= height + 1f,
                "$label must fit the actual ${width}x$height scene: $actual")
            assertTrue(clipped.width + 1f >= actual.width.value && clipped.height + 1f >= actual.height.value,
                "$label must not pass merely because its clipped intersection is visible: $actual / $clipped")
            if (control) assertTrue(actual.width.value >= 48f && actual.height.value >= 48f,
                "$label must retain its 48dp target: $actual")
            return node
        }

        fun readable(parent: String, text: String, scroll: Boolean = true) {
            val node = ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true)
            if (scroll) node.performScrollTo()
            node.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow }, "Text must wrap, not truncate: $text")
            val actual = node.getUnclippedBoundsInRoot()
            assertTrue(actual.left.value >= -1f && actual.right.value <= width + 1f,
                "Scrollable text must stay horizontally within the real scene: $actual")
        }

        fun closeRuntime(strings: AppStrings): SemanticsNodeInteraction = visible(
            ui.onNode(hasText(strings.text(TextKey.Close)) and hasClickAction() and hasAnyAncestor(hasTestTag("media-runtime-details"))),
            "runtime Close", control = true)

        fun progress(expected: Float) {
            val info = ui.onNodeWithTag("media-runtime-progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(expected, info.current)
            assertEquals(0f..1f, info.range)
        }

        fun legalDocument(index: Int, name: String) {
            // Known heading + original-language notice precede the six independently loaded
            // documents. Jump by index instead of scrolling through a complete GPL text.
            ui.onNodeWithTag("licenses-screen").performScrollToIndex(index + 2)
            ui.onNodeWithTag("legal-document-$name").assertExists()
        }

        fun capture(name: String, tag: String = "veyra-app") {
            require(name.startsWith("fixture-") && name.matches(Regex("[a-z0-9-]+")))
            val bitmap = ui.onNodeWithTag(tag).captureToImage()
            val pixels = bitmap.toPixelMap()
            val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
            val directory = coreShellOwnedDirectory("composeApp/build/test-evidence/design/recovery-runtime")
            fun ownedFile(extension: String) = File(directory, "$name.$extension").canonicalFile.also {
                require(it.toPath().startsWith(directory.toPath())) { "Refusing an escaping evidence path" }
            }
            check(ImageIO.write(output, "png", ownedFile("png")))
            ownedFile("txt").writeText("""
                AUTHORED UI FIXTURE — NOT RUNTIME INSTALLATION OR NATIVE/PHYSICAL PLAYBACK
                Test scene: $width x $height pixels at density 1; captured node: $tag.
                Capture: $name (${bitmap.width} x ${bitmap.height} pixels).
                Root cases use actual VeyraApp/AppContainer/Room with fake engine/runtime statuses.
                One-missing legal case uses the real legal screen with authored reader responses.
                No media acquisition, live source, catalog sockets, external browser or audible output.
                A generated file means only this test reached capture; inspect it independently.
                Run after lead slot: scripts/with-display.sh scripts/gradle.sh :composeApp:desktopTest --tests dev.veyra.app.RecoveryAndRuntimeUiTest
            """.trimIndent() + "\n")
        }
    }

    private companion object {
        const val DIAGNOSTIC_SENTINEL = "authored-internal-diagnostic-must-not-be-rendered"
        val DAMAGED_PRIMARY = "Authored damaged database; explicit recovery only.".encodeToByteArray()
        val LEGAL_NAMES = listOf("NOTICE.md", "LICENSE", "kmpYT-LICENSE", "kmpYT-THIRD_PARTY_NOTICES.md",
            "THIRD_PARTY_NOTICES.txt", "ibm_plex_sans_arabic_ofl.txt")
        fun authoredNotice(name: String) = "Authored local legal fixture for $name. Original-language text stays available."
    }
}
