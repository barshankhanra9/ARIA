package app.nocturne.player

import androidx.compose.runtime.Composable
import app.nocturne.player.data.model.AnimationIntensity
import app.nocturne.player.data.model.ArtworkShape
import app.nocturne.player.data.model.PaperAccent
import app.nocturne.player.data.model.ThemeMode
import app.nocturne.player.ui.theme.ModernPaperTheme

@Composable
fun NocturneTheme(
    content: @Composable () -> Unit
) {
    ModernPaperTheme(
        themeMode = ThemeMode.PAPER_LIGHT,
        accent = PaperAccent.TERRACOTTA,
        paperTextureIntensity = 0.65f,
        artworkShape = ArtworkShape.PHOTO_PRINT,
        animationIntensity = AnimationIntensity.FULL,
        fontScaleMultiplier = 1.0f,
        content = content
    )
}
