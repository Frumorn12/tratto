package it.frumorn.tratto.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/** Orientation et largeur utiles pour adapter la barre d'outils et la bibliothèque. */
data class WindowInfo(
    val paysage: Boolean,
    /** Largeur étroite (téléphone portrait, ou fenêtre étroite) : barres compactes. */
    val etroit: Boolean,
    val largeurDp: Int,
    val hauteurDp: Int,
)

@Composable
@ReadOnlyComposable
fun windowInfo(): WindowInfo {
    val c = LocalConfiguration.current
    return WindowInfo(
        paysage = c.orientation == Configuration.ORIENTATION_LANDSCAPE,
        etroit = c.screenWidthDp < 700,
        largeurDp = c.screenWidthDp,
        hauteurDp = c.screenHeightDp,
    )
}
