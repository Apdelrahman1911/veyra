package dev.veyra.app

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Only a worker may wait for the current initialization attempt; OS playback commands fail fast. */
internal suspend fun awaitApplicationInitialization(initialization: StateFlow<AppInitialization>) {
    applicationInitializationFailure(initialization.first { it !is AppInitialization.Loading })?.let {
        throw VeyraException(it)
    }
}

internal fun applicationInitializationFailure(initialization: AppInitialization?): AppFailure? = when (initialization) {
    AppInitialization.Ready -> null
    is AppInitialization.Failed -> initialization.failure
    else -> AppFailure(FailureCode.INVALID_STATE, "application_initializing")
}
