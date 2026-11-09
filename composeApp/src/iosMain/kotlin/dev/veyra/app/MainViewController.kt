@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LoadingState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.VeyraTheme
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    IosAppLifecycle.start()
    return ComposeUIViewController {
        val bootstrap by IosApplication.bootstrap.collectAsState()
        val state = bootstrap
        if (state is IosBootstrap.Available) VeyraApp(state.container)
        else VeyraTheme(isSystemInDarkTheme(), iosLanguage()) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        is IosBootstrap.Failed -> ErrorState(LocalStrings.current.errorFor(state.failure))
                        else -> LoadingState()
                    }
                }
            }
        }
    }
}
