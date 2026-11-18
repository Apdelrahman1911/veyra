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
        canvas = Color(0xFFF8F5FA),
        surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF0EAF4),
        surfaceContainerHighest = Color(0xFFE8E0EF),
        onSurface = Color(0xFF201925),
        onSurfaceVariant = Color(0xFF63556E),
        outline = Color(0xFF89758F),
        outlineSubtle = Color(0xFFDED4E5),
        primary = Color(0xFF70419A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEEDDF9),
        onPrimaryContainer = Color(0xFF38124E),
        secondary = Color(0xFF8E451E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFCE2D0),
        onSecondaryContainer = Color(0xFF4A220E),
        error = Color(0xFFAD292F),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFE2E1),
        onErrorContainer = Color(0xFF680A13),
        success = Color(0xFF196B44),
        warning = Color(0xFF785104),
    )

    val Dark = VeyraColorTokens(
        canvas = Color(0xFF08070B),
        surface = Color(0xFF141217),
        surfaceContainer = Color(0xFF1D1922),
        surfaceContainerHighest = Color(0xFF29232F),
        onSurface = Color(0xFFFAF6FF),
        onSurfaceVariant = Color(0xFFBEB1C9),
        outline = Color(0xFF9D8BAA),
        outlineSubtle = Color(0xFF39303F),
        primary = Color(0xFFD0A2F6),
        onPrimary = Color(0xFF30123E),
        primaryContainer = Color(0xFF321F40),
        onPrimaryContainer = Color(0xFFEEDDF9),
        secondary = Color(0xFFF0AC82),
        onSecondary = Color(0xFF3E1E0C),
        secondaryContainer = Color(0xFF492A1C),
        onSecondaryContainer = Color(0xFFFCE2D0),
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
    val Artwork = 18.dp
    val Card = 24.dp
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
    val family = remember(regular, medium, semibold, strings.isRtl) {
        if (strings.isRtl) FontFamily(regular, medium, semibold) else FontFamily.SansSerif
    }
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
            displayLarge = style(38, 46, FontWeight.SemiBold),
            displayMedium = style(34, 42, FontWeight.SemiBold),
            displaySmall = style(30, 38, FontWeight.SemiBold),
            headlineLarge = style(30, 38, FontWeight.SemiBold),
            headlineMedium = style(26, 34, FontWeight.SemiBold),
            headlineSmall = style(23, 31, FontWeight.SemiBold),
            titleLarge = style(22, 30, FontWeight.SemiBold),
            titleMedium = style(16, 23, FontWeight.Medium),
            titleSmall = style(15, 22, FontWeight.Medium),
            bodyLarge = style(16, 25, FontWeight.Normal),
            bodyMedium = style(14, 22, FontWeight.Normal),
            bodySmall = style(12, 19, FontWeight.Normal),
            labelLarge = style(14, 21, FontWeight.SemiBold),
            labelMedium = style(12, 19, FontWeight.Medium),
            labelSmall = style(11, 17, FontWeight.Medium),
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
