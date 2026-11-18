package dev.veyra.ui.design

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.LanguagePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The placeholder pattern initializes when AppStrings is first touched during the first
 * composition. Android's ICU regex is stricter than the desktop JVM's Pattern, so the
 * startup path must be exercised on a device, not only in common JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class AppStringsAndroidTest {
    @Test fun placeholdersInitializeAndFormatOnAndroid() {
        val strings = AppStrings(LanguagePreference.ENGLISH)
        val rendered = strings.format(TextKey.TrackCountDuration, "5", "1987")
        assertFalse(rendered.contains("{"))
        assertEquals("5 tracks · 1987", rendered.filterNot { it in '\u2066'..'\u2069' })
    }
}
