package it.frumorn.tratto.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.Strumento
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.nomePenna
import it.frumorn.tratto.ui.testo
import kotlin.math.roundToInt

/**
 * Menu del tasto dello stilo, tenuto premuto con la punta a mezz'aria.
 * Gli strumenti compaiono sotto la punta, senza lasciare la pagina.
 */
@Composable
fun StylusMenu(
    punto: Offset?,
    strumenti: StatoStrumenti,
    puoAnnullare: Boolean,
    puoRipetere: Boolean,
    onChiudi: () -> Unit,
    onPenna: (Penna) -> Unit,
    onGomma: () -> Unit,
    onLazo: () -> Unit,
    onAnnulla: () -> Unit,
    onRipeti: () -> Unit,
) {
    val dove = punto ?: return
    Box(Modifier.offset { IntOffset(dove.x.roundToInt(), dove.y.roundToInt()) }.size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = onChiudi, shape = MaterialTheme.shapes.medium) {
            Penna.entries.forEach { p ->
                val sel = strumenti.strumento == Strumento.PENNA && strumenti.penna == p
                DropdownMenuItem(
                    text = { Text(nomePenna(p)) },
                    leadingIcon = { Icona(iconaPennaStilo(p), null) },
                    trailingIcon = { if (sel) Icona(R.drawable.ic_check, null) },
                    onClick = { onChiudi(); onPenna(p) },
                )
            }
            DropdownMenuItem(
                text = { Text(testo(R.string.eraser)) },
                leadingIcon = { Icona(R.drawable.ic_ink_eraser, null) },
                trailingIcon = { if (strumenti.strumento == Strumento.GOMMA) Icona(R.drawable.ic_check, null) },
                onClick = { onChiudi(); onGomma() },
            )
            DropdownMenuItem(
                text = { Text(testo(R.string.lasso)) },
                leadingIcon = { Icona(R.drawable.ic_lasso_select, null) },
                trailingIcon = { if (strumenti.strumento == Strumento.LAZO) Icona(R.drawable.ic_check, null) },
                onClick = { onChiudi(); onLazo() },
            )
            DropdownMenuItem(
                text = { Text(testo(R.string.undo)) },
                leadingIcon = { Icona(R.drawable.ic_undo, null) },
                enabled = puoAnnullare,
                onClick = { onChiudi(); onAnnulla() },
            )
            DropdownMenuItem(
                text = { Text(testo(R.string.redo)) },
                leadingIcon = { Icona(R.drawable.ic_redo, null) },
                enabled = puoRipetere,
                onClick = { onChiudi(); onRipeti() },
            )
        }
    }
}

private fun iconaPennaStilo(p: Penna) = when (p) {
    Penna.STILOGRAFICA -> R.drawable.ic_ink_pen
    Penna.PENNA -> R.drawable.ic_stylus
    Penna.MATITA -> R.drawable.ic_edit
    Penna.EVIDENZIATORE -> R.drawable.ic_ink_highlighter
    Penna.PENNELLO -> R.drawable.ic_brush
}
