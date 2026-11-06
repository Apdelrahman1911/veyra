package dev.veyra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LoadingState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.VeyraTheme

/** Activity recreation only recreates UI. It never creates/releases a player or media session. */
class MainActivity : ComponentActivity() {
    private val app get() = application as VeyraApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val bootstrap by app.bootstrap.collectAsState()
            val state = bootstrap
            if (state is AndroidBootstrap.Available) {
                val navigation by state.container.navigation.collectAsState()
                BackHandler(enabled = navigation.canGoBack) { state.container.navigateBack() }
                VeyraApp(state.container)
            }
            else VeyraTheme(isSystemInDarkTheme(), applicationLanguage(LocalConfiguration.current)) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                        when (state) {
                            is AndroidBootstrap.Failed -> ErrorState(LocalStrings.current.errorFor(state.failure))
                            else -> LoadingState()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); app.onVisible() }
    override fun onStop() { app.onHidden(); app.checkpointPlayback(); super.onStop() }
}
