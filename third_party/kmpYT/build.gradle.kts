// The included build uses Veyra's toolchain and the original kmpYT module configuration.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
}

val sourceVersion = libs.versions.kmpyt.get()
allprojects {
    group = "io.github.apdelrahman1911.kmpyt"
    version = sourceVersion
}
