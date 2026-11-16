package it.frumorn.tratto.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.ui.theme.TrattoMotion

@Composable
fun Icona(@DrawableRes res: Int, descrizione: String?, modifier: Modifier = Modifier, tinta: Color = LocalContentColor.current, dimensione: Dp = 24.dp) {
    Icon(painterResource(res), descrizione, modifier.size(dimensione), tint = tinta)
}

/** Rimpicciolisce leggermente l'elemento mentre e' premuto. */
fun Modifier.premibile(interazione: MutableInteractionSource): Modifier = composed {
    val premuto by interazione.collectIsPressedAsState()
    val scala by animateFloatAsState(if (premuto) TrattoMotion.PRESS_SCALE else 1f, tween(120), label = "pressione")
    graphicsLayer { scaleX = scala; scaleY = scala }
}

/** Bottone rotondo con icona: 48 dp di area tattile, stato attivo e disattivo animati. */
@Composable
fun BottoneIcona(
    @DrawableRes icona: Int,
    descrizione: String,
    modifier: Modifier = Modifier,
    attivo: Boolean = true,
    selezionato: Boolean = false,
    forma: Shape = CircleShape,
    dimensione: Dp = 48.dp,
    onClick: () -> Unit,
) {
    val interazione = remember { MutableInteractionSource() }
    val sfondo by animateColorAsState(
        if (selezionato) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), label = "sfondo",
    )
    val colore = when {
        !attivo -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        selezionato -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tinta by animateColorAsState(colore, tween(160), label = "tinta")
    Box(
        modifier
            .size(dimensione)
            .premibile(interazione)
            .clip(forma)
            .background(sfondo)
            .clickable(interazione, ripple(), enabled = attivo, role = Role.Button, onClickLabel = descrizione, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icona(icona, descrizione, tinta = tinta)
    }
}
