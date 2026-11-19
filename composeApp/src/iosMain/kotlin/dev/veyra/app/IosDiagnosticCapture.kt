@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.veyra.app

import dev.veyra.diagnostics.DiagnosticCaptureState
import dev.veyra.diagnostics.DiagnosticJournal
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

private const val enabledKey = "veyra.diagnostics.enabled.v1"
private const val sessionKey = "veyra.diagnostics.session.v1"
private const val linkKey = "veyra.diagnostics.link-key.v1"

internal fun iosDiagnosticJournal(scope: CoroutineScope, directory: String,
    preferences: NSUserDefaults = NSUserDefaults.standardUserDefaults): DiagnosticJournal {
    val session = preferences.stringForKey(sessionKey)?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    return DiagnosticJournal(scope, directory.toPath() / "diagnostics",
        initial = DiagnosticCaptureState(preferences.boolForKey(enabledKey), session),
        save = { state ->
            preferences.setObject(state.sessionId, forKey = sessionKey)
            preferences.setBool(state.enabled, forKey = enabledKey)
        }, key = {
            preferences.stringForKey(linkKey)?.let { stored -> runCatching { stored.decodeHex() }.getOrNull() }
                ?.takeIf { it.size == 16 } ?: Uuid.random().toByteArray().toByteString().also {
                preferences.setObject(it.hex(), forKey = linkKey)
            }
        }, prepareDirectory = { path ->
            val location = path.toString()
            val protected = NSFileManager.defaultManager.setAttributes(
                mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
                ofItemAtPath = location, error = null)
            val excluded = NSURL.fileURLWithPath(location).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
            if (!protected || !excluded) throw VeyraException(AppFailure(FailureCode.STORAGE_IO))
        })
}

internal suspend fun shareIosDiagnostics(path: Path) = withContext(Dispatchers.Main.immediate) {
    val window = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        .flatMap { it.windows.filterIsInstance<UIWindow>() }.firstOrNull { it.isKeyWindow() }
    var presenter = window?.rootViewController ?: throw VeyraException(AppFailure(FailureCode.INVALID_STATE))
    while (presenter.presentedViewController != null) presenter = checkNotNull(presenter.presentedViewController)
    check(presenter.view?.window != null && presenter !is UIActivityViewController)
    val share = UIActivityViewController(listOf(NSURL.fileURLWithPath(path.toString())), applicationActivities = null)
    share.popoverPresentationController?.let {
        val view = checkNotNull(presenter.view)
        it.sourceView = view
        it.sourceRect = view.bounds
    }
    presenter.presentViewController(share, animated = true, completion = null)
}
