package dev.veyra.platform.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.OfflineAsset
import dev.veyra.playback.AssetValidation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAssetValidatorTest {
    @Test fun decodesAuthoredLocalAudioAndRejectsWrongSizeMissingMalformedAndOutsideRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "validator-${UUID.randomUUID()}").apply { mkdirs() }
        val outside = authoredWave(File(context.cacheDir, "outside-${UUID.randomUUID()}.wav"))
        try {
            val valid = authoredWave(File(root, "tone.wav"))
            val validator = AndroidAssetValidator(root)
            val asset = OfflineAsset("asset", "media", valid.path, valid.length(), mimeType = "audio/wav")
            assertTrue(validator.validate(asset) is AssetValidation.Valid)
            assertTrue(validator.validate(asset.copy(sizeBytes = asset.sizeBytes + 1)) is AssetValidation.Invalid)
            assertTrue(validator.validate(asset.copy(uri = File(root, "missing.wav").path)) is AssetValidation.Invalid)
            val broken = File(root, "broken.wav").apply { writeBytes(ByteArray(500) { 0x42 }) }
            assertTrue(validator.validate(asset.copy(uri = broken.path, sizeBytes = broken.length())) is AssetValidation.Invalid)
            assertTrue(validator.validate(asset.copy(uri = outside.path, sizeBytes = outside.length())) is AssetValidation.Invalid)
        } finally { outside.delete(); root.deleteRecursively() }
    }
}
