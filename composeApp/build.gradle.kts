import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
}

val hostOs = System.getProperty("os.name").lowercase()
val hostArch = System.getProperty("os.arch").lowercase()
val nativeOs = when {
    hostOs.contains("win") -> "windows"
    hostOs.contains("mac") -> "macos"
    hostOs.contains("linux") -> "linux"
    else -> error("No verified desktop runtime for $hostOs")
}
val nativeArch = if (hostArch in listOf("aarch64", "arm64")) "arm64" else "x64"

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "Veyra"
            isStatic = true
            binaryOption("bundleId", "dev.veyra.app.shared")
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.session)
            implementation(libs.coroutines.android)
            implementation(libs.coroutines.guava)
            implementation(libs.work.runtime)
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test:core:1.6.1")
            implementation("androidx.test.ext:junit:1.2.1")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.dbus.core)
                implementation(libs.dbus.native)
                implementation(libs.commons.compress)
                implementation(libs.ktor.cio)
                runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-$nativeOs-$nativeArch:${libs.versions.mpvRuntime.get()}")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

android {
    namespace = "dev.veyra.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.veyra.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    buildTypes { release { isMinifyEnabled = false } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dev.veyra.generated.resources"
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "dev.veyra.desktop.MainKt"
        buildTypes.release.proguard { isEnabled = false }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Veyra"
            packageVersion = "1.0.0"
            description = "Veyra — music and podcasts, online and offline"
            vendor = "Veyra contributors"
            copyright = "Copyright 2026 Veyra contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            includeAllModules = true
            linux {
                packageName = "veyra"
                debMaintainer = "Apdelrahman1911@users.noreply.github.com"
                menuGroup = "AudioVideo"
            }
            windows {
                menuGroup = "Veyra"
                shortcut = true
                upgradeUuid = "aebfc901-b3a1-4c57-b20e-4bbdf1968e08"
            }
            macOS { bundleID = "dev.veyra.app" }
        }
    }
}
