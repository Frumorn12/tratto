package it.frumorn.tratto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import it.frumorn.tratto.R

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private fun s(size: Int, line: Int, w: FontWeight, track: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = w,
    letterSpacing = track.em,
)

val TrattoType = Typography(
    displayLarge = s(52, 55, FontWeight.Normal, -0.025),
    displayMedium = s(44, 48, FontWeight.Normal, -0.025),
    displaySmall = s(36, 39, FontWeight.Normal, -0.025),
    headlineLarge = s(32, 36, FontWeight.Normal, -0.02),
    headlineMedium = s(28, 32, FontWeight.Medium, -0.02),
    headlineSmall = s(24, 30, FontWeight.Medium, -0.02),
    titleLarge = s(22, 29, FontWeight.Medium, -0.02),
    titleMedium = s(18, 25, FontWeight.SemiBold, -0.015),
    titleSmall = s(14, 20, FontWeight.SemiBold),
    bodyLarge = s(17, 25, FontWeight.Normal, -0.01),
    bodyMedium = s(15, 22, FontWeight.Normal),
    bodySmall = s(13, 20, FontWeight.Normal),
    labelLarge = s(14, 20, FontWeight.Medium),
    labelMedium = s(12, 16, FontWeight.Medium, 0.06),
    labelSmall = s(11, 16, FontWeight.Medium),
)

/** Sopratitolo in maiuscolo per le etichette di sezione. */
val Eyebrow = s(12, 16, FontWeight.SemiBold, 0.09)
