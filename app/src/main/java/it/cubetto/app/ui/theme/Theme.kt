package it.cubetto.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.cubetto.app.R

object Cubo {
    val Ground = Color(0xFFF4F2EE)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1F1D1A)
    val Muted = Color(0xFF6B6660)
    val Line = Color(0xFFE4DFD7)
    val Track = Color(0xFFEFEBE4)
    val Yellow = Color(0xFFFFD43B)
    val YellowSoft = Color(0xFFFFF6D6)
    val YellowInk = Color(0xFF5E4A00)
    val Red = Color(0xFFE3262B)
    val RedDark = Color(0xFFC81E23)
    val Green = Color(0xFF2E9E5B)
    val GreenSoft = Color(0xFFD6F0DF)
    val GreenInk = Color(0xFF1E6B3E)
    val Blue = Color(0xFF3C82C4)
    val BlueSoft = Color(0xFFEAF3FC)
    val BlueInk = Color(0xFF1E4E7A)
    val Orange = Color(0xFFFFE6CC)
    val OrangeInk = Color(0xFF6A3400)
    val Indigo = Color(0xFFE2E6F5)
    val IndigoInk = Color(0xFF2F3F7A)
}

/** Colori delle statuine: sfondo della card e corpo del personaggio. */
val FigurePalette = listOf(
    Color(0xFFDCEBFA) to Color(0xFF7C7A86),
    Color(0xFFFFF1C2) to Color(0xFFE89B2C),
    Color(0xFFE3F4E6) to Color(0xFF4FA36B),
    Color(0xFFFCE3E6) to Color(0xFFD9536A),
    Color(0xFFEDE6FA) to Color(0xFF7E5BC2),
    Color(0xFFDCEBFA) to Color(0xFF3C82C4),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Fredoka = FontFamily(variable(R.font.fredoka, 500), variable(R.font.fredoka, 600), variable(R.font.fredoka, 700))
val Nunito = FontFamily(variable(R.font.nunito, 600), variable(R.font.nunito, 700), variable(R.font.nunito, 800))

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(700), fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(700), fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(700), fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(600), fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(600), fontSize = 17.sp),
    titleSmall = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight(500), fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(700), fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(700), fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(700), fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 13.sp),
    labelSmall = TextStyle(fontFamily = Nunito, fontWeight = FontWeight(800), fontSize = 11.sp),
)

@Composable
fun CubettoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Cubo.Red,
            onPrimary = Color.White,
            secondary = Cubo.Yellow,
            onSecondary = Cubo.Ink,
            background = Cubo.Ground,
            onBackground = Cubo.Ink,
            surface = Cubo.Card,
            onSurface = Cubo.Ink,
            surfaceVariant = Cubo.Track,
            onSurfaceVariant = Cubo.Muted,
            surfaceContainer = Cubo.Card,
            surfaceContainerHigh = Cubo.Card,
            surfaceContainerLow = Cubo.Ground,
            outline = Cubo.Line,
            error = Cubo.RedDark,
        ),
        typography = typography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}
