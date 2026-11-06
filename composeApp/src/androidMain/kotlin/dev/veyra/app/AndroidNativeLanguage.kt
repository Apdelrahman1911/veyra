package dev.veyra.app

import android.content.Context
import android.content.res.Configuration
import dev.veyra.model.LanguagePreference
import java.util.Locale

/** A local resource context only; changing Veyra's language never mutates the OS/global locale. */
internal fun nativeLanguageContext(context: Context, preference: LanguagePreference): Context {
    val language = if (preference == LanguagePreference.SYSTEM) applicationLanguage(context.resources.configuration) else preference
    val locale = Locale.forLanguageTag(if (language == LanguagePreference.ARABIC) "ar" else "en")
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    })
}
