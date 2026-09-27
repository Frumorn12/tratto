package it.frumorn.tratto.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing

/** Curve e durate delle animazioni (entrata 320 ms, uscita 190 ms). */
object TrattoMotion {
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = FastOutSlowInEasing
    const val ENTER_MS = 320
    const val EXIT_MS = 190
    const val STAGGER_MS = 30
    const val PRESS_SCALE = 0.94f
}
