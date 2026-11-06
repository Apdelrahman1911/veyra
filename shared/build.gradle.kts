import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.room)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.ktor.core)
            api(libs.okio)
            api(libs.room.runtime)
            implementation(libs.sqlite.bundled)
            api("io.github.apdelrahman1911.kmpyt:kmpyt:0.1.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
            implementation(libs.room.testing)
        }
        androidMain.dependencies {
            implementation(libs.ktor.android)
            implementation(libs.coroutines.android)
        }
        jvmMain.dependencies { implementation(libs.ktor.cio) }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    add("kspIosX64", libs.room.compiler)
}

room { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "dev.veyra.shared"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
