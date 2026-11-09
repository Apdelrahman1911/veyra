package dev.veyra.app

import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode

/** OS playback commands must not be retained until a user explicitly recovers a failed library. */
internal fun applicationInitializationFailure(initialization: AppInitialization?): AppFailure? = when (initialization) {
    AppInitialization.Ready -> null
    is AppInitialization.Failed -> initialization.failure
    else -> AppFailure(FailureCode.INVALID_STATE, "application_initializing")
}
