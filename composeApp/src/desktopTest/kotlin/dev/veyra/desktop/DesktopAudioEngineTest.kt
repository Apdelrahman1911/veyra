package dev.veyra.desktop

import dev.veyra.desktop.audio.DesktopAssetValidator
import dev.veyra.desktop.audio.DesktopSelfTest
import dev.veyra.desktop.runtime.DesktopNativeRuntime
import dev.veyra.desktop.runtime.DesktopRuntimeConfig
import dev.veyra.model.OfflineAsset
import dev.veyra.playback.AssetValidation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopAudioEngineTest {
    private fun config(): DesktopRuntimeConfig {
        val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val work = root.resolve(".work/desktop-audio/kotlin-tests")
        Files.createDirectories(work)
        return DesktopRuntimeConfig(work, silentTestOutput = true)
    }

    @Test fun platformSelectionRejectsUnsupportedArchitectures() {
        assertTrue(DesktopNativeRuntime.platform("Linux", "amd64") == "linux-x64")
        assertTrue(DesktopNativeRuntime.platform("Mac OS X", "aarch64") == "macos-arm64")
        assertFailsWith<IllegalArgumentException> { DesktopNativeRuntime.platform("Linux", "arm64") }
        assertFailsWith<IllegalStateException> { DesktopNativeRuntime.platform("Windows 11", "x86") }
    }

    @Test fun packagedNativeCodecControlsAndTlsConfiguration() = runBlocking {
        val config = config()
        val fixture = config.dataDirectory.resolve("tone.mp3")
        javaClass.getResourceAsStream("/dev/veyra/desktop/tone.mp3")!!.use { input -> Files.copy(input, fixture, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        val report = DesktopSelfTest.run(fixture, config)
        val result = Json.parseToJsonElement(report).jsonObject
        assertEquals(true, result.getValue("success").jsonPrimitive.boolean)
        assertEquals(false, result.getValue("physicalAudioVerified").jsonPrimitive.boolean)
        assertEquals("yes", result.getValue("native").jsonObject.getValue("tlsVerify").jsonPrimitive.content)
        assertEquals(listOf("prepare", "play", "pause", "seekForward", "rate", "seekBack", "stop"),
            result.getValue("operations").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun standaloneValidatorRejectsHtmlAndWrongLengths(): Unit = runBlocking {
        val config = config()
        val path = config.dataDirectory.resolve("not-audio.mp3")
        Files.writeString(path, "<!doctype html><html>Unavailable</html>")
        val validator = DesktopAssetValidator(config)
        assertIs<AssetValidation.Invalid>(validator.validate(OfflineAsset("bad", "bad", path.toUri().toString(), Files.size(path))))
        assertIs<AssetValidation.Invalid>(validator.validate(OfflineAsset("bad-size", "bad", path.toString(), 1)))
    }
}
