import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.PathSensitivity
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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
    // Resolve the exact scoped Git Bash on Windows; a bare bash can select the WSL shim.
    val trustedBash = providers.environmentVariable("VEYRA_TRUSTED_BASH").orNull
        ?: if (nativeOs == "windows") error("Run through scripts/gradle.sh to select Git Bash") else "bash"
    commandLine(trustedBash, rootProject.file("scripts/dev.sh").absolutePath, "python3", "-B",
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
                // This artifact supplies native resources only. Its published dependency on
                // the MediaMP Kotlin wrapper would otherwise replace our pinned Compose
                // runtime/test graph (and Kotlin stdlib) without changing compilation inputs.
                // Veyra loads raw libmpv through its own explicitly declared JNA adapter.
                runtimeOnly("org.openani.mediamp:mediamp-mpv-runtime-$nativeOs-$nativeArch:${libs.versions.mpvRuntime.get()}") {
                    isTransitive = false
                }
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
        // --info has its own defaults. The lifecycle48 run produced fresh
        // passing XML but no method events without this explicit level.
        info {
            events("started", "passed", "failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
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

// Production-only legal replacement. Ordinary source/developer builds keep the
// tracked source-only preview under composeResources/files/legal unchanged.
// scripts/package.sh passes the exact generate-notices output here after it has
// exported the host runtime inventory. The prefix is the one observed in the
// retained application JAR and produced by desktopProcessResources today; the
// manifest verifier checks the same constant independently.
val desktopLegalResourcePrefix = "composeResources/dev.veyra.generated.resources/files/legal"
val desktopLegalDirectory = providers.gradleProperty("veyra.desktopLegalDir").orNull
if (desktopLegalDirectory != null) {
    val projectRoot = rootProject.projectDir.toPath().toAbsolutePath().normalize()
    val realProjectRoot = rootProject.projectDir.toPath().toRealPath()
    val supplied = rootProject.file(desktopLegalDirectory).toPath().toAbsolutePath().normalize()
    if (!supplied.startsWith(projectRoot)) {
        throw GradleException("veyra.desktopLegalDir escapes the repository")
    }
    var ancestor: Path? = supplied
    while (true) {
        val current = ancestor ?: break
        if (current == projectRoot) break
        if (Files.isSymbolicLink(current)) {
            throw GradleException("veyra.desktopLegalDir may not contain a symlink")
        }
        ancestor = current.parent
    }
    if (!Files.isDirectory(supplied, LinkOption.NOFOLLOW_LINKS)) {
        throw GradleException("veyra.desktopLegalDir must be an existing directory")
    }
    val realLegal = supplied.toRealPath()
    if (!realLegal.startsWith(realProjectRoot) || !Files.isRegularFile(realLegal.resolve("index.json"), LinkOption.NOFOLLOW_LINKS)) {
        throw GradleException("veyra.desktopLegalDir must contain index.json and remain inside the repository")
    }
    var legalFiles = 0
    var legalBytes = 0L
    val stream = Files.walk(realLegal)
    try {
        val iterator = stream.iterator()
        while (iterator.hasNext()) {
            val path = iterator.next()
            if (path == realLegal) continue
            if (Files.isSymbolicLink(path)) {
                throw GradleException("Generated legal resources may not contain symlinks")
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw GradleException("Generated legal resources must be regular files")
            }
            val size = Files.size(path)
            legalFiles += 1
            legalBytes += size
            if (legalFiles > 512 || size > 64L * 1024 * 1024 || legalBytes > 256L * 1024 * 1024) {
                throw GradleException("Generated legal resources exceed their bounded file/size limits")
            }
        }
    } finally {
        stream.close()
    }
    // Gradle copies and applies child include/exclude patterns to each spec's
    // source-relative paths. The root exclude matches the desktopMain resource
    // path (composeResources/.../legal/...), while the replacement from() uses
    // the generated legal directory itself as its source root, so its entries
    // are relative names (LICENSE, index.json, notices/...) and are retained.
    tasks.withType<ProcessResources>().matching { it.name == "desktopProcessResources" }.configureEach {
        exclude("$desktopLegalResourcePrefix/**")
        from(realLegal.toFile()) {
            into(desktopLegalResourcePrefix)
        }
        inputs.dir(realLegal.toFile())
            .withPropertyName("veyraDesktopLegalResources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        doFirst {
            // Do not let incremental output keep a stale source-only legal file.
            val staged = destinationDir.resolve(desktopLegalResourcePrefix)
            if (staged.exists() && !staged.deleteRecursively()) {
                throw GradleException("Could not clear the previous desktop legal resource subtree")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.veyra.desktop.MainKt"
        buildTypes.release.proguard { isEnabled = false }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
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
