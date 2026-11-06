import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

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
val nativeArch = when (hostArch) {
    "aarch64", "arm64" -> "arm64"
    "amd64", "x86_64" -> "x64"
    else -> error("No verified desktop architecture for $hostArch")
}
val nativePlatform = "$nativeOs-$nativeArch"
val desktopNativeResources = rootProject.layout.projectDirectory.dir(".work/desktop-native/resources/$nativePlatform")

// Production run/distribution tasks prepare matching-host resources before the
// resource input snapshot and JAR. Plain compilation/tests remain independent
// of CMake/FFmpeg; a plain desktopJar is not a verified distribution artifact.
val prepareDesktopNativeResources by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Build/verify source-pinned desktop helpers and stage exact classpath resources"
    workingDir(rootProject.projectDir)
    commandLine("bash", rootProject.file("scripts/dev.sh").absolutePath, "python3", "-B",
        rootProject.file("native/bundle.py").absolutePath, "--target", nativePlatform)
    inputs.files(rootProject.fileTree("native") { exclude("**/__pycache__/**") })
    inputs.file(rootProject.file("LICENSE"))
    inputs.property("nativePlatform", nativePlatform)
    outputs.dir(desktopNativeResources)
    // Recheck existing build receipts/bytes even when Gradle considers its own
    // outputs unchanged. The native recipe safely reuses valid FFmpeg output.
    outputs.upToDateWhen { false }
}

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
            resources.srcDir(desktopNativeResources)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.dbus.core)
                implementation(libs.dbus.native)
                implementation(libs.commons.compress)
                implementation(libs.ktor.cio)
                implementation(libs.ktor.encoding)
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

val desktopProductionTasks = setOf(
    "run", "runRelease", "runDistributable", "runReleaseDistributable",
    "createDistributable", "createReleaseDistributable",
    "packageDistributionForCurrentOS", "packageReleaseDistributionForCurrentOS",
    "packageDeb", "packageReleaseDeb", "packageDmg", "packageReleaseDmg",
    "packageMsi", "packageReleaseMsi", "packageExe", "packageReleaseExe",
    "packageRpm", "packageReleaseRpm",
)
tasks.matching { it.name in desktopProductionTasks }.configureEach {
    dependsOn(prepareDesktopNativeResources)
}
tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    mustRunAfter(prepareDesktopNativeResources)
}

// Compose flattens dependency archives for these tasks. That destroys the
// identity of the whole-hash-pinned libmpv runtime JAR. Reject the task graph
// before ANY task executes, including in --dry-run; do not silently skip it.
val unsupportedDesktopUberJarTasks = setOf(
    "packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS",
)
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name in unsupportedDesktopUberJarTasks }) {
        throw GradleException("Veyra does not support fat/uber-JAR distributions: they rewrite the verified native-runtime archive. " +
            "Use createDistributable or a native installer. A plain desktopJar is not a standalone distribution.")
    }
}

// Bound each test worker independently of the compiler/Gradle daemon. Keep
// full result events in the log so a killed daemon cannot erase which method
// was running or the failure details. A truncated run is never a passing run.
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "384m"
    jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:MaxMetaspaceSize=256m")
    timeout.set(Duration.ofMinutes(15))
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
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
