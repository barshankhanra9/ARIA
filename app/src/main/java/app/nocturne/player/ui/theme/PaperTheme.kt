package app.nocturne.player.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nocturne.player.R
import app.nocturne.player.data.model.AnimationIntensity
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.ThemeMode

val EditorialDisplayFont = FontFamily(Font(R.font.playfair_display, FontWeight.Normal))
val EditorialBodyFont = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal))
val EditorialMonoFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

@Immutable
data class PaperPalette(
    val isDark: Boolean,
    val isAmoled: Boolean,
    val paperBackground: Color,
    val paperCream: Color,
    val paperElevated: Color,
    val paperPressed: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val divider: Color,
    val edgeHighlight: Color,
    val ambientShadow: Color,
    val photoMatte: Color,
    val danger: Color
)

@Immutable
data class PaperMotionSpec(
    val intensity: AnimationIntensity,
    val systemReducedMotion: Boolean
) {
    val isReduced: Boolean get() = systemReducedMotion || intensity == AnimationIntensity.REDUCED
    val pressScale: Float get() = when {
        isReduced -> 1f
        intensity == AnimationIntensity.SUBTLE -> 0.99f
        else -> 0.976f
    }
    val springSpec get() = spring<Float>(
        dampingRatio = if (intensity == AnimationIntensity.FULL) 0.76f else 0.92f,
        stiffness = Spring.StiffnessMediumLow
    )
    val durationMs: Int get() = when {
        isReduced -> 60
        intensity == AnimationIntensity.SUBTLE -> 190
        else -> 280
    }
}

@Immutable
data class PaperStyleConfig(
    val palette: PaperPalette,
    val textureIntensity: Float,
    val artworkShape: ArtworkShape,
    val motion: PaperMotionSpec,
    val fontScale: Float
)

private val LocalPaperStyle = staticCompositionLocalOf {
    PaperStyleConfig(
        palette = buildPaperPalette(ThemeMode.PAPER_LIGHT, PaperAccent.TERRACOTTA),
        textureIntensity = 0.65f,
        artworkShape = ArtworkShape.PHOTO_PRINT,
        motion = PaperMotionSpec(AnimationIntensity.FULL, false),
        fontScale = 1.0f
    )
}

object AriaPaperTheme {
    val style: PaperStyleConfig
        @Composable get() = LocalPaperStyle.current
    val palette: PaperPalette
        @Composable get() = LocalPaperStyle.current.palette
    val motion: PaperMotionSpec
        @Composable get() = LocalPaperStyle.current.motion
}

fun buildPaperPalette(mode: ThemeMode, accent: PaperAccent): PaperPalette {
    return when (mode) {
        ThemeMode.PAPER_LIGHT -> {
            val acc = Color(accent.lightHex)
            PaperPalette(
                isDark = false,
                isAmoled = false,
                paperBackground = Color(0xFFF3EDE1),
                paperCream = Color(0xFFEAE1CF),
                paperElevated = Color(0xFFFAF6ED),
                paperPressed = Color(0xFFDFD3BE),
                inkPrimary = Color(0xFF171411),
                inkSecondary = Color(0xFF5A5247),
                inkTertiary = Color(0xFF847A6D),
                accent = acc,
                onAccent = Color(0xFFFDF9F2),
                accentSoft = acc.copy(alpha = 0.14f),
                divider = Color(0xFFCEC3AE),
                edgeHighlight = Color(0xFFFFFDF8),
                ambientShadow = Color(0xFF2B2015),
                photoMatte = Color(0xFFFDF9F0),
                danger = Color(0xFFAC3B33)
            )
        }
        ThemeMode.PAPER_DARK -> {
            val acc = Color(accent.darkHex)
            PaperPalette(
                isDark = true,
                isAmoled = false,
                paperBackground = Color(0xFF181613),
                paperCream = Color(0xFF221F1B),
                paperElevated = Color(0xFF2B2722),
                paperPressed = Color(0xFF12100E),
                inkPrimary = Color(0xFFF0E9DD),
                inkSecondary = Color(0xFFA79E92),
                inkTertiary = Color(0xFF7B7368),
                accent = acc,
                onAccent = Color(0xFF191410),
                accentSoft = acc.copy(alpha = 0.18f),
                divider = Color(0xFF36312A),
                edgeHighlight = Color(0xFF3C362E),
                ambientShadow = Color(0xFF000000),
                photoMatte = Color(0xFFEFE8DA),
                danger = Color(0xFFD96B62)
            )
        }
        ThemeMode.AMOLED_BLACK -> {
            val acc = Color(accent.darkHex)
            PaperPalette(
                isDark = true,
                isAmoled = true,
                paperBackground = Color(0xFF000000),
                paperCream = Color(0xFF0D0C0B),
                paperElevated = Color(0xFF171513),
                paperPressed = Color(0xFF060505),
                inkPrimary = Color(0xFFF3EDE2),
                inkSecondary = Color(0xFFA1988C),
                inkTertiary = Color(0xFF726B61),
                accent = acc,
                onAccent = Color(0xFF120E0B),
                accentSoft = acc.copy(alpha = 0.20f),
                divider = Color(0xFF25211D),
                edgeHighlight = Color(0xFF2A2621),
                ambientShadow = Color(0xFF000000),
                photoMatte = Color(0xFFEAE2D3),
                danger = Color(0xFFE0736A)
            )
        }
    }
}

private fun buildPaperTypography(scale: Float): Typography {
    fun Float.s() = (this * scale).sp
    return Typography(
        displayLarge = TextStyle(
            fontFamily = EditorialDisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 35f.s(),
            lineHeight = 40f.s(),
            letterSpacing = (-0.8).sp
        ),
        headlineLarge = TextStyle(
            fontFamily = EditorialDisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 28f.s(),
            lineHeight = 33f.s(),
            letterSpacing = (-0.5).sp
        ),
        headlineMedium = TextStyle(
            fontFamily = EditorialDisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 23f.s(),
            lineHeight = 28f.s(),
            letterSpacing = (-0.3).sp
        ),
        headlineSmall = TextStyle(
            fontFamily = EditorialDisplayFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19f.s(),
            lineHeight = 24f.s(),
            letterSpacing = (-0.2).sp
        ),
        titleLarge = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18f.s(),
            lineHeight = 23f.s()
        ),
        titleMedium = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15f.s(),
            lineHeight = 20f.s()
        ),
        titleSmall = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.Medium,
            fontSize = 13f.s(),
            lineHeight = 18f.s()
        ),
        bodyLarge = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 15f.s(),
            lineHeight = 22f.s()
        ),
        bodyMedium = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 13.5f.s(),
            lineHeight = 19f.s()
        ),
        bodySmall = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.Normal,
            fontSize = 12f.s(),
            lineHeight = 16f.s()
        ),
        labelLarge = TextStyle(
            fontFamily = EditorialBodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13f.s(),
            lineHeight = 18f.s(),
            letterSpacing = 0.2.sp
        ),
        labelMedium = TextStyle(
            fontFamily = EditorialMonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 11f.s(),
            lineHeight = 15f.s(),
            letterSpacing = 1.1.sp
        ),
        labelSmall = TextStyle(
            fontFamily = EditorialMonoFont,
            fontWeight = FontWeight.Medium,
            fontSize = 10f.s(),
            lineHeight = 14f.s(),
            letterSpacing = 1.2.sp
        )
    )
}

private val PaperShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

@Composable
fun ModernPaperTheme(
    themeMode: ThemeMode,
    accent: PaperAccent,
    paperTextureIntensity: Float,
    artworkShape: ArtworkShape,
    animationIntensity: AnimationIntensity,
    fontScaleMultiplier: Float,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemReduced = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    val palette = remember(themeMode, accent) { buildPaperPalette(themeMode, accent) }
    val typography = remember(fontScaleMultiplier) { buildPaperTypography(fontScaleMultiplier.coerceIn(0.85f, 1.2f)) }
    val motion = remember(animationIntensity, systemReduced) {
        PaperMotionSpec(animationIntensity, systemReduced)
    }
    val styleConfig = remember(palette, paperTextureIntensity, artworkShape, motion, fontScaleMultiplier) {
        PaperStyleConfig(
            palette = palette,
            textureIntensity = paperTextureIntensity.coerceIn(0f, 1f),
            artworkShape = artworkShape,
            motion = motion,
            fontScale = fontScaleMultiplier
        )
    }

    val m3Scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            background = palette.paperBackground,
            onBackground = palette.inkPrimary,
            surface = palette.paperCream,
            onSurface = palette.inkPrimary,
            surfaceVariant = palette.paperElevated,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.divider,
            error = palette.danger
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            onSecondary = palette.onAccent,
            background = palette.paperBackground,
            onBackground = palette.inkPrimary,
            surface = palette.paperCream,
            onSurface = palette.inkPrimary,
            surfaceVariant = palette.paperElevated,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.divider,
            error = palette.danger
        )
    }

    CompositionLocalProvider(LocalPaperStyle provides styleConfig) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = typography,
            shapes = PaperShapes,
            content = content
        )
    }
}
