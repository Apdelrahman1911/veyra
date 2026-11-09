package dev.veyra.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.veyra.generated.resources.Res
import dev.veyra.generated.resources.ibm_plex_sans_arabic_medium
import dev.veyra.generated.resources.ibm_plex_sans_arabic_regular
import dev.veyra.generated.resources.ibm_plex_sans_arabic_semibold
import dev.veyra.model.LanguagePreference
import org.jetbrains.compose.resources.Font

/** Opaque sRGB pairs. Never use outlineSubtle for essential controls or text. */
@Immutable
data class VeyraColorTokens(
    val canvas: Color,
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceContainerHighest: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineSubtle: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val success: Color,
    val warning: Color,
)

object VeyraPalette {
    val Light = VeyraColorTokens(
        canvas = Color(0xFFF6F8F8),
        surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFEDF2F3),
        surfaceContainerHighest = Color(0xFFE0EAED),
        onSurface = Color(0xFF15252D),
        onSurfaceVariant = Color(0xFF465F6B),
        outline = Color(0xFF647D89),
        outlineSubtle = Color(0xFFCDDADF),
        primary = Color(0xFF00696A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFBDEDEB),
        onPrimaryContainer = Color(0xFF003736),
        secondary = Color(0xFF855223),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF9DFC3),
        onSecondaryContainer = Color(0xFF3B2209),
        error = Color(0xFFAD292F),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE2E1),
        onErrorContainer = Color(0xFF680A13),
        success = Color(0xFF196B44),
        warning = Color(0xFF785104),
    )

    val Dark = VeyraColorTokens(
        canvas = Color(0xFF0D161B),
        surface = Color(0xFF14232A),
        surfaceContainer = Color(0xFF1C2E37),
        surfaceContainerHighest = Color(0xFF253D47),
        onSurface = Color(0xFFE8F0F3),
        onSurfaceVariant = Color(0xFFAFC2CC),
        outline = Color(0xFF819AA5),
        outlineSubtle = Color(0xFF314852),
        primary = Color(0xFF76D7D4),
        onPrimary = Color(0xFF003735),
        primaryContainer = Color(0xFF164E50),
        onPrimaryContainer = Color(0xFFBDEDEB),
        secondary = Color(0xFFE5B88A),
        onSecondary = Color(0xFF442B11),
        secondaryContainer = Color(0xFF604322),
        onSecondaryContainer = Color(0xFFF9DFC3),
        error = Color(0xFFFFB3B5),
        onError = Color(0xFF680A13),
        errorContainer = Color(0xFF552025),
        onErrorContainer = Color(0xFFFFDADB),
        success = Color(0xFF85D3AA),
        warning = Color(0xFFEFBC66),
    )
}

val LocalVeyraColors = staticCompositionLocalOf { VeyraPalette.Light }

object VeyraSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val Section = 24.dp
    val Wide = 32.dp
    val ExtraWide = 48.dp
    val Hero = 64.dp
    val TouchTarget = 48.dp
    val CompactInset = 16.dp
    val MediumInset = 24.dp
    val ExpandedInset = 32.dp
}

object VeyraRadii {
    val Small = 8.dp
    val Artwork = 12.dp
    val Card = 20.dp
}

object VeyraBreakpoints {
    const val MediumWidth = 600
    const val ExpandedWidth = 840
    const val LargeWidth = 1200
    const val ExtraLargeWidth = 1600
    const val CompactHeight = 480
}

/** Shell resolves SYSTEM using the platform locale. MediaDirection handles narrow LTR islands. */
@Composable
fun VeyraTheme(
    darkTheme: Boolean,
    language: LanguagePreference,
    content: @Composable () -> Unit,
) {
    val strings = remember(language) { AppStrings(language) }
    val colors = if (darkTheme) VeyraPalette.Dark else VeyraPalette.Light
    val colorScheme = remember(darkTheme) {
        if (darkTheme) darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary, onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer, onSecondaryContainer = colors.onSecondaryContainer,
            tertiary = colors.secondary, onTertiary = colors.onSecondary,
            tertiaryContainer = colors.secondaryContainer, onTertiaryContainer = colors.onSecondaryContainer,
            background = colors.canvas, onBackground = colors.onSurface,
            surface = colors.surface, onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceContainerHighest, onSurfaceVariant = colors.onSurfaceVariant,
            surfaceTint = colors.primary,
            inverseSurface = VeyraPalette.Light.surfaceContainer,
            inverseOnSurface = VeyraPalette.Light.onSurface,
            inversePrimary = VeyraPalette.Light.primary,
            error = colors.error, onError = colors.onError,
            errorContainer = colors.errorContainer, onErrorContainer = colors.onErrorContainer,
            outline = colors.outline, outlineVariant = colors.outlineSubtle,
            scrim = Color.Black,
            surfaceDim = colors.canvas,
            surfaceBright = colors.surfaceContainerHighest,
            surfaceContainerLowest = colors.canvas,
            surfaceContainerLow = colors.surface,
            surfaceContainer = colors.surfaceContainer,
            surfaceContainerHigh = colors.surfaceContainerHighest,
            surfaceContainerHighest = colors.surfaceContainerHighest,
        ) else lightColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary,
            primaryContainer = colors.primaryContainer, onPrimaryContainer = colors.onPrimaryContainer,
            secondary = colors.secondary, onSecondary = colors.onSecondary,
            secondaryContainer = colors.secondaryContainer, onSecondaryContainer = colors.onSecondaryContainer,
            tertiary = colors.secondary, onTertiary = colors.onSecondary,
            tertiaryContainer = colors.secondaryContainer, onTertiaryContainer = colors.onSecondaryContainer,
            background = colors.canvas, onBackground = colors.onSurface,
            surface = colors.surface, onSurface = colors.onSurface,
            surfaceVariant = colors.surfaceContainerHighest, onSurfaceVariant = colors.onSurfaceVariant,
            surfaceTint = colors.primary,
            inverseSurface = VeyraPalette.Dark.surfaceContainer,
            inverseOnSurface = VeyraPalette.Dark.onSurface,
            inversePrimary = VeyraPalette.Dark.primary,
            error = colors.error, onError = colors.onError,
            errorContainer = colors.errorContainer, onErrorContainer = colors.onErrorContainer,
            outline = colors.outline, outlineVariant = colors.outlineSubtle,
            scrim = Color.Black,
            surfaceDim = colors.surfaceContainerHighest,
            surfaceBright = colors.surface,
            surfaceContainerLowest = colors.surface,
            surfaceContainerLow = colors.canvas,
            surfaceContainer = colors.surfaceContainer,
            surfaceContainerHigh = colors.surfaceContainerHighest,
            surfaceContainerHighest = colors.surfaceContainerHighest,
        )
    }
    val regular = Font(Res.font.ibm_plex_sans_arabic_regular, FontWeight.Normal)
    val medium = Font(Res.font.ibm_plex_sans_arabic_medium, FontWeight.Medium)
    val semibold = Font(Res.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold)
    val family = remember(regular, medium, semibold) { FontFamily(regular, medium, semibold) }
    val typography = remember(family, strings.language) {
        val locale = LocaleList(Locale(if (strings.isRtl) "ar" else "en"))
        fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
            fontFamily = family,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = 0.sp,
            textDirection = TextDirection.Content,
            localeList = locale,
        )
        Typography(
            displayLarge = style(36, 48, FontWeight.SemiBold),
            displayMedium = style(32, 44, FontWeight.SemiBold),
            displaySmall = style(28, 40, FontWeight.SemiBold),
            headlineLarge = style(28, 40, FontWeight.SemiBold),
            headlineMedium = style(24, 36, FontWeight.SemiBold),
            headlineSmall = style(22, 34, FontWeight.SemiBold),
            titleLarge = style(22, 34, FontWeight.SemiBold),
            titleMedium = style(17, 28, FontWeight.Medium),
            titleSmall = style(16, 26, FontWeight.Medium),
            bodyLarge = style(17, 28, FontWeight.Normal),
            bodyMedium = style(16, 26, FontWeight.Normal),
            bodySmall = style(14, 22, FontWeight.Normal),
            labelLarge = style(15, 24, FontWeight.SemiBold),
            labelMedium = style(14, 22, FontWeight.Medium),
            labelSmall = style(14, 22, FontWeight.Medium),
        )
    }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalVeyraColors provides colors,
        LocalLayoutDirection provides if (strings.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(VeyraRadii.Small),
                medium = RoundedCornerShape(VeyraRadii.Artwork),
                large = RoundedCornerShape(VeyraRadii.Card),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
