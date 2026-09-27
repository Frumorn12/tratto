package it.frumorn.tratto.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palette di Tratto: viola profondo su fondi chiari, ossidiana per il tema scuro.
val DeepViola = Color(0xFF6546F3)
val Ossidiana = Color(0xFF271E52)
val OssidianaDeep = Color(0xFF1F1747)
val OssidianaLight = Color(0xFF493990)
val ViolaLight = Color(0xFFA491FA)
val OffBlack = Color(0xFF1E1E1E)

val TrattoLight = lightColorScheme(
    primary = DeepViola,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFEDFE),
    onPrimaryContainer = Ossidiana,
    inversePrimary = ViolaLight,
    secondary = OssidianaLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E3F0),
    onSecondaryContainer = OssidianaDeep,
    tertiary = Color(0xFF17324D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E2EE),
    onTertiaryContainer = Color(0xFF0B1B2B),
    error = Color(0xFFC7455C),
    onError = Color.White,
    errorContainer = Color(0xFFF8E9EB),
    onErrorContainer = Color(0xFF5C1422),
    background = Color.White,
    onBackground = OffBlack,
    surface = Color.White,
    onSurface = OffBlack,
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF4D4D4D),
    surfaceTint = DeepViola,
    inverseSurface = OffBlack,
    inverseOnSurface = Color(0xFFF2F2F2),
    outline = Color(0xFF8A8A94),
    outlineVariant = Color(0xFFE0E0E6),
    scrim = OssidianaDeep,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDEDEE3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F4),
    surfaceContainerHigh = Color(0xFFEDEDF1),
    surfaceContainerHighest = Color(0xFFE6E6EB),
)

val TrattoDark = darkColorScheme(
    primary = ViolaLight,
    onPrimary = OssidianaDeep,
    primaryContainer = OssidianaLight,
    onPrimaryContainer = Color(0xFFEAE5FF),
    inversePrimary = DeepViola,
    secondary = Color(0xFFCDC1FF),
    onSecondary = Ossidiana,
    secondaryContainer = Color(0xFF312760),
    onSecondaryContainer = Color(0xFFE6E0FF),
    tertiary = Color(0xFFA9C3DC),
    onTertiary = Color(0xFF0E2133),
    tertiaryContainer = Color(0xFF17324D),
    onTertiaryContainer = Color(0xFFD6E2EE),
    error = Color(0xFFFF7281),
    onError = Color(0xFF3B0A12),
    errorContainer = Color(0xFF6B1F2B),
    onErrorContainer = Color(0xFFFFD9DE),
    background = OffBlack,
    onBackground = Color(0xFFF2F2F2),
    surface = OffBlack,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF353535),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = ViolaLight,
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = OffBlack,
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFF353535),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF1A1A1A),
    surfaceContainerLowest = Color(0xFF141414),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF2E2E2E),
    surfaceContainerHighest = Color(0xFF353535),
)

/** Colori che Material 3 non prevede: stati e il colore del foglio. */
@Immutable
data class TrattoExtraColors(
    val success: Color,
    val warning: Color,
    val paper: Color,
    val paperLine: Color,
    val desk: Color,
)

val LightExtra = TrattoExtraColors(
    success = Color(0xFF167D5A),
    warning = Color(0xFF9D6815),
    paper = Color.White,
    paperLine = Color(0xFFD9D6EC),
    desk = Color(0xFFF2F2F4),
)

val DarkExtra = TrattoExtraColors(
    success = Color(0xFF34C88A),
    warning = Color(0xFFFFBF62),
    paper = Color(0xFF262626),
    paperLine = Color(0xFF3E3A52),
    desk = Color(0xFF141414),
)

val LocalTrattoColors = staticCompositionLocalOf { LightExtra }
