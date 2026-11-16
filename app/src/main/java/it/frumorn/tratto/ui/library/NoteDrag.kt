package it.frumorn.tratto.ui.library

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Appui long puis glisser, comme dans Drive : le défilement de la grille reste libre tant que
 * le doigt bouge avant la fin de l'appui long.
 */
@Composable
internal fun Modifier.souleverNote(
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onFin: () -> Unit,
    onAnnule: () -> Unit,
): Modifier {
    var coordonnees by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val demarre = rememberUpdatedState(onStart)
    val bouge = rememberUpdatedState(onMove)
    val fin = rememberUpdatedState(onFin)
    val annule = rememberUpdatedState(onAnnule)
    fun racine(local: Offset): Offset? {
        val c = coordonnees ?: return null
        if (!c.isAttached) return null
        return c.localToRoot(local)
    }
    return this
        .onGloballyPositioned { coordonnees = it }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { local -> racine(local)?.let { demarre.value(it) } },
                onDrag = { change, _ -> racine(change.position)?.let { bouge.value(it) } },
                onDragEnd = { fin.value() },
                onDragCancel = { annule.value() },
            )
        }
}

internal data class Glissement(
    val ids: Set<String>,
    val titre: String,
    val x: Float,
    val y: Float,
    val cible: String?,
)

internal fun zoneSous(zones: Map<String, Rect>, x: Float, y: Float): String? {
    val p = Offset(x, y)
    val touches = zones.entries.filter { p in it.value }
    val barre = touches.firstOrNull { it.key.startsWith("barre|") }
    return (barre ?: touches.firstOrNull())?.key?.substringAfter('|')
}

/** Coin haut-gauche du conteneur, pour placer le fantôme dans ses coordonnées. */
@Composable
internal fun Modifier.origineRacine(onOrigine: (Offset) -> Unit): Modifier {
    val maj = rememberUpdatedState(onOrigine)
    return this.onGloballyPositioned { maj.value(it.positionInRoot()) }
}

