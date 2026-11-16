package it.frumorn.tratto.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R

/**
 * Spettro HSV: quadrato saturazione/luminosità + barra della tinta.
 * [colore] e [onColore] usano ARGB opaco (0xFFrrggbb), come il resto di Tratto.
 */
@Composable
fun SpettroColore(
    colore: Int,
    onColore: (Int) -> Unit,
    modifier: Modifier = Modifier,
    altezza: Dp = 148.dp,
) {
    val opaco = colore or (0xFF shl 24)
    val iniziale = remember {
        FloatArray(3).also { AndroidColor.colorToHSV(opaco, it) }
    }
    val h = remember { mutableFloatStateOf(iniziale[0]) }
    val s = remember { mutableFloatStateOf(iniziale[1]) }
    val v = remember { mutableFloatStateOf(iniziale[2]) }
    val onColoreAttuale by rememberUpdatedState(onColore)

    LaunchedEffect(opaco) {
        if (hsvToArgb(h.floatValue, s.floatValue, v.floatValue) != opaco) {
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(opaco, hsv)
            h.floatValue = hsv[0]
            s.floatValue = hsv[1]
            v.floatValue = hsv[2]
        }
    }

    // Letture per il ridisegno (Compose osserva i MutableFloatState).
    val hue = h.floatValue
    val sat = s.floatValue
    val val_ = v.floatValue

    val descrizione = testo(R.string.choose_the_color)
    val densita = LocalDensity.current
    Column(modifier.semantics { contentDescription = descrizione }) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(altezza)
                .clip(RoundedCornerShape(10.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        s.floatValue = (down.position.x / size.width).coerceIn(0f, 1f)
                        v.floatValue = 1f - (down.position.y / size.height).coerceIn(0f, 1f)
                        onColoreAttuale(hsvToArgb(h.floatValue, s.floatValue, v.floatValue))
                        drag(down.id) { change ->
                            s.floatValue = (change.position.x / size.width).coerceIn(0f, 1f)
                            v.floatValue = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            onColoreAttuale(hsvToArgb(h.floatValue, s.floatValue, v.floatValue))
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                    }
                },
        ) {
            val tinta = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, tinta)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val cx = sat * size.width
                val cy = (1f - val_) * size.height
                val r = 8f * densita.density
                drawCircle(Color.White, r, Offset(cx, cy), style = Stroke(2.5f * densita.density))
                drawCircle(
                    Color.Black.copy(alpha = 0.35f),
                    r + 1.5f * densita.density,
                    Offset(cx, cy),
                    style = Stroke(1.2f * densita.density),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        h.floatValue = (down.position.x / size.width).coerceIn(0f, 1f) * 360f
                        onColoreAttuale(hsvToArgb(h.floatValue, s.floatValue, v.floatValue))
                        drag(down.id) { change ->
                            h.floatValue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                            onColoreAttuale(hsvToArgb(h.floatValue, s.floatValue, v.floatValue))
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                    }
                },
        ) {
            val tinte = remember {
                listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.horizontalGradient(tinte))
                val cx = (hue / 360f) * size.width
                val r = 7f * densita.density
                drawCircle(Color.White, r, Offset(cx, size.height / 2f), style = Stroke(2.5f * densita.density))
                drawCircle(
                    Color.Black.copy(alpha = 0.35f),
                    r + 1.2f * densita.density,
                    Offset(cx, size.height / 2f),
                    style = Stroke(1.1f * densita.density),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(opaco))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), CircleShape),
        )
    }
}

internal fun hsvToArgb(h: Float, s: Float, v: Float): Int =
    AndroidColor.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
