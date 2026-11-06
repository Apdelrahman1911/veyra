package dev.veyra.desktop

import dev.veyra.app.MediaRuntimeComponent
import dev.veyra.app.MediaRuntimePhase
import dev.veyra.desktop.runtime.DesktopMediaRuntime
import dev.veyra.desktop.runtime.DesktopProcessContext
import dev.veyra.desktop.runtime.HelperArchiveFetcher
import dev.veyra.desktop.runtime.LazyJavaScriptRuntime
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Authored archive/lifecycle tests, not network source or Chromium sandbox evidence. */
class DesktopMediaRuntimeTest {
    @Test fun startupAndSynchronousJsPrepareNeverDownloadOrUseDeveloperPathTools() = runBlocking {
        withHelperWork { root ->
            DesktopProcessContext.acquire(root.resolve("data")).use { context ->
                var requests = 0
                val runtime = DesktopMediaRuntime(context, suppliedFetcher = HelperArchiveFetcher { _, _, _ -> requests++; error("Unexpected request") })
                val js = LazyJavaScriptRuntime(runtime)
                try {
                    assertEquals(MediaRuntimePhase.REQUIRED, runtime.status.value.phase)
                    assertFailsWith<VeyraException> { js.prepare("authored test program") }
                    assertEquals(0, requests)
                    assertFalse(Files.exists(context.dataDirectory.resolve("runtime")))
                } finally { js.close(); runtime.close() }
            }
        }
    }

    @Test fun verifiedInstallationSurvivesRestartWithoutNetworkAndRejectsMutableHashReceipts() = runBlocking {
        withHelperWork { root ->
            DesktopProcessContext.acquire(root.resolve("data")).use { context ->
                val node = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool")), component = "node")
                val chromium = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool", "chromium fixture".encodeToByteArray())), component = "chromium")
                val ffmpeg = Files.writeString(root.resolve("ffmpeg"), "authored non-executable fixture")
                var requests = 0
                val fetcher = HelperArchiveFetcher { spec, path, progress ->
                    requests++
                    Files.copy(if (spec.component == "node") node.first else chromium.first, path)
                    progress(spec.archiveBytes)
                }
                fun runtime() = DesktopMediaRuntime(context, manifestProvider = { component, _ -> if (component == "node") node.second else chromium.second },
                    suppliedFetcher = fetcher, suppliedFfmpeg = { ffmpeg })
                val first = runtime()
                val installed: Path
                try {
                    first.prepare(); first.prepare()
                    installed = first.requirePrepared().node
                    assertEquals(MediaRuntimePhase.READY, first.status.value.phase)
                    assertEquals(2, requests)
                } finally { first.close() }
                // An interrupted prior process may leave only its exact .setup-* temporary tree.
                val stale = Files.createDirectory(installed.parent.parent.resolve(".setup-interrupted"))
                Files.writeString(stale.resolve("download.archive"), "incomplete")
                val second = runtime()
                try {
                    second.prepare()
                    assertEquals(2, requests)
                    assertFalse(Files.exists(stale, NOFOLLOW_LINKS))
                } finally { second.close() }
                Files.writeString(installed, "tampered") // Same length as original authored file.
                val third = runtime()
                try {
                    assertFailsWith<VeyraException> { third.prepare() }
                    assertEquals(MediaRuntimePhase.ERROR, third.status.value.phase)
                    assertEquals(2, requests, "Tampered live code must not silently trigger a network overwrite")
                    assertEquals("tampered", Files.readString(installed))
                } finally { third.close() }
            }
        }
    }

    @Test fun callerCancellationCleansStageAndAllowsANewWaitingCallerToFinish() = runBlocking {
        withHelperWork { root ->
            DesktopProcessContext.acquire(root.resolve("data")).use { context ->
                val node = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool")), component = "node")
                val chromium = node.second.copy(component = "chromium")
                val ffmpeg = Files.writeString(root.resolve("ffmpeg"), "authored")
                val entered = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                var requests = 0
                val runtime = DesktopMediaRuntime(context, manifestProvider = { component, _ -> if (component == "node") node.second else chromium },
                    suppliedFfmpeg = { ffmpeg }, suppliedFetcher = HelperArchiveFetcher { spec, destination, progress ->
                        requests++
                        if (requests == 1) {
                            Files.write(destination, byteArrayOf(1), CREATE_NEW)
                            progress(1); entered.complete(Unit)
                            try { CompletableDeferred<Unit>().await() } finally { cancelled.complete(Unit) }
                        } else { Files.copy(node.first, destination); progress(spec.archiveBytes) }
                    })
                try {
                    val first = async(start = CoroutineStart.UNDISPATCHED) { runtime.prepare() }
                    withTimeout(5_000) { entered.await() }
                    val second = async { runtime.prepare() }
                    first.cancelAndJoin()
                    withTimeout(5_000) { cancelled.await(); second.await() }
                    assertEquals(MediaRuntimePhase.READY, runtime.status.value.phase)
                    assertEquals(3, requests)
                    assertNoStages(context.dataDirectory)
                } finally { runtime.close() }
            }
        }
    }

    @Test fun ownerCloseCancelsAndJoinsAnInProgressInstallAndRefusesFurtherSetup() = runBlocking {
        withHelperWork { root ->
            DesktopProcessContext.acquire(root.resolve("data")).use { context ->
                val fixture = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool")))
                val entered = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
                val runtime = DesktopMediaRuntime(context, manifestProvider = { _, _ -> fixture.second }, suppliedFfmpeg = { fixture.first },
                    suppliedFetcher = HelperArchiveFetcher { _, path, progress ->
                        Files.write(path, byteArrayOf(1), CREATE_NEW); progress(1); entered.complete(Unit)
                        try { CompletableDeferred<Unit>().await() } finally { stopped.complete(Unit) }
                    })
                val operation = async { runtime.prepare() }
                try {
                    withTimeout(5_000) { entered.await(); runtime.close(); stopped.await() }
                    operation.join()
                    assertTrue(operation.isCancelled)
                    assertNoStages(context.dataDirectory)
                    assertFailsWith<IllegalStateException> { runtime.prepare() }
                    assertFalse(runtime.status.value.phase == MediaRuntimePhase.READY)
                } finally { runtime.close(); operation.cancelAndJoin() }
            }
        }
    }

    @Test fun corruptedArchiveAndMissingPackagedFfmpegNeverPublishReadyOrKeepPartialStages() = runBlocking {
        withHelperWork { root ->
            DesktopProcessContext.acquire(root.resolve("data")).use { context ->
                val fixture = helperArchive(root, "zip", listOf(ArchiveFixtureEntry("bundle/tool")))
                var requests = 0
                val corrupt = DesktopMediaRuntime(context, manifestProvider = { _, _ -> fixture.second }, suppliedFfmpeg = { fixture.first },
                    suppliedFetcher = HelperArchiveFetcher { _, path, _ -> requests++; Files.write(path, byteArrayOf(1), CREATE_NEW) })
                try {
                    assertFailsWith<VeyraException> { corrupt.prepare() }
                    assertEquals(MediaRuntimePhase.ERROR, corrupt.status.value.phase)
                    assertNoStages(context.dataDirectory)
                    assertFailsWith<VeyraException> { corrupt.requirePrepared() }
                } finally { corrupt.close() }
                val missing = DesktopMediaRuntime(context, suppliedFfmpeg = {
                    throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "bundled_ffmpeg_missing"))
                }, suppliedFetcher = HelperArchiveFetcher { _, _, _ -> requests++; error("No request expected") })
                try {
                    assertFailsWith<VeyraException> { missing.prepare() }
                    assertEquals(MediaRuntimeComponent.FFMPEG, missing.status.value.component)
                    assertEquals(MediaRuntimePhase.ERROR, missing.status.value.phase)
                    assertEquals(1, requests)
                } finally { missing.close() }
            }
        }
    }

    private fun assertNoStages(root: Path) {
        Files.walk(root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".setup-") }) }
    }
}
