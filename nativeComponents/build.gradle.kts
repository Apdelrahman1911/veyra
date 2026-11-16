import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
}

// Compile a deliberately small public UI surface from the pinned nativeCmpkit source.
// Its publishing build requires a newer compiler; Veyra retains its tested toolchain.
val kit = rootProject.file("third_party/nativeCmpkit")
check(kit.resolve("LICENSE").isFile) { "Initialize third_party/nativeCmpkit before building." }
val ui = kit.resolve("calf-ui/src")
val core = kit.resolve("calf-core/src/commonMain/kotlin")

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64(); iosSimulatorArm64(); iosX64()
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain {
            kotlin.srcDirs(ui.resolve("commonMain/kotlin"), core)
            kotlin.include(
                "com.mohamedrejeb.calf/core/InternalCalfApi.kt",
                "com.mohamedrejeb.calf/core/ExperimentalCalfApi.kt",
                "com/mohamedrejeb/calf/ui/ExperimentalCalfUiApi.kt",
                "com/mohamedrejeb/calf/ui/dialog/**",
                "com/mohamedrejeb/calf/ui/uikit/IosKeyboardType.kt",
                "com/mohamedrejeb/calf/ui/button/AdaptiveIconButton.kt",
                "com/mohamedrejeb/calf/ui/button/LiquidGlassButtonColors.kt",
                "com/mohamedrejeb/calf/ui/button/CupertinoButton.kt",
                "com/mohamedrejeb/calf/ui/button/CupertinoIndication.kt",
                "com/mohamedrejeb/calf/ui/toggle/AdaptiveSwitch.kt",
                "com/mohamedrejeb/calf/ui/slider/AdaptiveSlider.kt",
                "com/mohamedrejeb/calf/ui/progress/AdaptiveCircularProgressIndicator.kt",
                "com/mohamedrejeb/calf/ui/cupertino/CupertinoCircularProgressIndicator.kt",
                "dev/veyra/nativecomponents/**",
            )
            dependencies {
                api(compose.runtime); api(compose.foundation); api(compose.material3)
                implementation(libs.coroutines.core)
            }
        }
        val materialMain by creating {
            dependsOn(commonMain.get())
            kotlin.srcDir(ui.resolve("materialMain/kotlin"))
            kotlin.include(
                "com/mohamedrejeb/calf/ui/dialog/**",
                "com/mohamedrejeb/calf/ui/button/AdaptiveIconButton.material.kt",
                "com/mohamedrejeb/calf/ui/toggle/AdaptiveSwitch.material.kt",
                "dev/veyra/nativecomponents/**",
                "com/mohamedrejeb/calf/ui/progress/AdaptiveCircularProgressIndicator.material.kt",
            )
        }
        androidMain { dependsOn(materialMain) }
        val desktopMain by getting { dependsOn(materialMain) }
        iosMain {
            kotlin.srcDir(ui.resolve("iosMain/kotlin"))
            kotlin.include(
                "com/mohamedrejeb/calf/ui/dialog/**",
                "com/mohamedrejeb/calf/ui/utils/ThemeUtils.kt",
                "com/mohamedrejeb/calf/ui/utils/IosKeyboardTypeUtils.kt",
                "com/mohamedrejeb/calf/ui/progress/AdaptiveCircularProgressIndicator.ios.kt",
                "dev/veyra/nativecomponents/**",
            )
        }
    }
}
android {
    namespace = "dev.veyra.nativecomponents"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Bind the consumer sources to the reviewed, clean submodule instead of a moving branch.
val verifyNativeCmpkitSource by tasks.registering {
    val pinFile = rootProject.layout.projectDirectory.file("gradle/nativecmpkit.commit")
    inputs.file(pinFile)
    outputs.upToDateWhen { false }
    doLast {
        val expected = pinFile.asFile.readText().trim()
        val actual = providers.exec { workingDir(kit); commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
        check(actual == expected) { "nativeCmpkit revision differs from gradle/nativecmpkit.commit" }
        val changes = providers.exec { workingDir(kit); commandLine("git", "status", "--porcelain", "--untracked-files=all") }.standardOutput.asText.get().trim()
        check(changes.isEmpty()) { "nativeCmpkit must be clean; keep Veyra compatibility code in nativeComponents." }
    }
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "compileDebugKotlinAndroid" || it.name == "compileReleaseKotlinAndroid" }
    .configureEach { dependsOn(verifyNativeCmpkitSource) }
