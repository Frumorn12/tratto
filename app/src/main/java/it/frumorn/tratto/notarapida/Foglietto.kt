package it.frumorn.tratto.notarapida

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.Strumento
import it.frumorn.tratto.ink.Pennelli
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.editor.GrigliaColori
import it.frumorn.tratto.ui.premibile
import it.frumorn.tratto.ui.theme.LocalTrattoColors
import it.frumorn.tratto.ui.theme.TrattoMotion

/**
 * Velo sopra l'app sottostante e, al centro, il foglietto: barra compatta degli strumenti e sotto
 * la superficie di scrittura dell'editor. Entra ingrandendosi appena, esce al contrario.
 */
@Composable
fun Foglietto(
    sessione: SessioneNotaRapida,
    inUscita: Boolean,
    ultimaPenna: () -> Long,
    onChiudi: () -> Unit,
    onApriInTratto: () -> Unit,
    onUscitaFinita: () -> Unit,
) {
    val colori = LocalTrattoColors.current
    LaunchedEffect(colori) { sessione.applicaColori(colori.paper.toArgb(), colori.desk.toArgb(), colori.paperLine.toArgb()) }

    val comparsa = remember { Animatable(0f) }
    LaunchedEffect(inUscita) {
        if (!inUscita) {
            comparsa.animateTo(1f, tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter))
        } else {
            comparsa.animateTo(0f, tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit))
            onUscitaFinita()
        }
    }
    BackHandler(enabled = !inUscita) { onChiudi() }

    var foglio by remember { mutableStateOf(Rect.Zero) }
    var pannello by remember { mutableStateOf(false) }
    val velo = MaterialTheme.colorScheme.scrim

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(velo, alpha = 0.45f * comparsa.value.coerceIn(0f, 1f)) }
            // Tocchi fuori dal foglietto: guardati dall'esterno, dopo che il foglietto ha preso i suoi.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val giu = awaitFirstDown(requireUnconsumed = false)
                    // Il palmo puo' arrivare come tipo sconosciuto: non chiude mai.
                    if (foglio.contains(giu.position) || giu.type == PointerType.Unknown) return@awaitEachGesture
                    val conPenna = giu.type != PointerType.Touch
                    val msDallaPenna = giu.uptimeMillis - ultimaPenna()
                    var spostato = false
                    var piuDita = false
                    var annullato = false
                    var fine = giu.uptimeMillis
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } > 1) piuDita = true
                        ev.changes.firstOrNull { it.id == giu.id }?.let { c ->
                            if ((c.position - giu.position).getDistance() > viewConfiguration.touchSlop) spostato = true
                            // Un gesto annullato dal sistema (per esempio il palmo riconosciuto) arriva gia' consumato.
                            if (!c.pressed && c.isConsumed) annullato = true
                            fine = c.uptimeMillis
                        }
                        if (ev.changes.none { it.pressed }) break
                    }
                    if (!annullato && NotaRapida.toccoFuoriChiude(conPenna, fine - giu.uptimeMillis, spostato, piuDita, msDallaPenna)) onChiudi()
                }
            },
    ) {
        val (larghezza, altezza) = NotaRapida.dimensioni(maxWidth.value, maxHeight.value)
        val forma = MaterialTheme.shapes.extraLarge
        // L'inchiostro in corso si disegna in una SurfaceView sopra la finestra: dentro un livello
        // di Compose con ritaglio o trasparenza non si vedeva e il tratto compariva solo al
        // rilascio della penna. Per questo il livello dell'animazione c'e' solo mentre il foglietto
        // entra o esce, e la superficie di scrittura non sta dentro la forma arrotondata: sfondo e
        // ombra sono un riquadro a parte, dietro.
        val p = comparsa.value.coerceIn(0f, 1f)
        val animazione = if (p >= 1f) Modifier else Modifier.graphicsLayer {
            val scala = 0.9f + 0.1f * p
            scaleX = scala; scaleY = scala
            alpha = p
            translationY = (1f - p) * 24.dp.toPx()
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .onGloballyPositioned { foglio = it.boundsInRoot() }
                .then(animazione)
                .size(larghezza.dp, altezza.dp),
        ) {
            Box(Modifier.matchParentSize().shadow(18.dp, forma).background(colori.desk, forma))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.matchParentSize()) {
                    BarraFoglietto(sessione, compatta = larghezza < 720f, pannello = pannello, onPannello = { pannello = it }, onChiudi = onChiudi, onApriInTratto = onApriInTratto)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Staccata dai bordi quanto basta perche' gli angoli arrotondati restino puliti.
                    AreaScrittura(
                        sessione, pannello, onPannello = { pannello = it },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** La superficie di scrittura con, sopra, il pannello dei colori quando e' aperto. */
@Composable
private fun AreaScrittura(s: SessioneNotaRapida, pannello: Boolean, onPannello: (Boolean) -> Unit, modifier: Modifier) {
    // Posizione dell'area, per dire all'inchiostro in corso dove non disegnare (sopra il pannello).
    val area = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Box(modifier.onGloballyPositioned { area[0] = it }) {
        AndroidView(factory = { s.vista }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(
            visible = pannello,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp),
            enter = scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.9f, transformOrigin = TransformOrigin(0f, 0f)) + fadeIn(tween(160)),
            exit = scaleOut(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit), targetScale = 0.94f, transformOrigin = TransformOrigin(0f, 0f)) + fadeOut(tween(TrattoMotion.EXIT_MS)),
        ) {
            DisposableEffect(Unit) { onDispose { s.vista.impostaMaschera(emptyList()) } }
            val st = s.strumenti
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.onGloballyPositioned { c ->
                    val a = area[0] ?: return@onGloballyPositioned
                    val r = a.localBoundingBoxOf(c)
                    s.vista.impostaMaschera(listOf(RectF(r.left, r.top, r.right, r.bottom)))
                },
            ) {
                Column(Modifier.padding(16.dp)) {
                    GrigliaColori(StatoStrumenti.TAVOLOZZA, st.corrente.colore) { s.scegliColore(it); onPannello(false) }
                    if (st.coloriRecenti.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("RECENTI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        GrigliaColori(st.coloriRecenti, st.corrente.colore) { s.scegliColore(it); onPannello(false) }
                    }
                }
            }
        }
    }
}

/**
 * Barra compatta: le penne (con colore e spessore dell'ultima volta), gomma, colore, annulla e
 * ripeti, poi "Apri in Tratto" e "Fatto". Se il foglietto e' stretto le azioni restano solo icone.
 */
@Composable
private fun BarraFoglietto(
    s: SessioneNotaRapida,
    compatta: Boolean,
    pannello: Boolean,
    onPannello: (Boolean) -> Unit,
    onChiudi: () -> Unit,
    onApriInTratto: () -> Unit,
) {
    val st = s.strumenti
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Penna.entries.forEach { p ->
                val sel = st.strumento == Strumento.PENNA && st.penna == p
                PennaFoglietto(p, sel, st.penne.getValue(p).colore) { s.cambiaStrumenti(st.copy(strumento = Strumento.PENNA, penna = p)) }
            }
            BottoneIcona(R.drawable.ic_ink_eraser, "Gomma", dimensione = 44.dp, selezionato = st.strumento == Strumento.GOMMA) {
                s.cambiaStrumenti(st.copy(strumento = if (st.strumento == Strumento.GOMMA) Strumento.PENNA else Strumento.GOMMA))
            }
            PallinoColore(st, pannello) { onPannello(!pannello) }
            Spacer(Modifier.weight(1f))
            BottoneIcona(R.drawable.ic_undo, "Annulla", dimensione = 44.dp, attivo = s.puoAnnullare) { s.annulla() }
            BottoneIcona(R.drawable.ic_redo, "Ripeti", dimensione = 44.dp, attivo = s.puoRipetere) { s.ripeti() }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (compatta) {
                BottoneIcona(R.drawable.ic_fullscreen, "Apri in Tratto", dimensione = 44.dp, onClick = onApriInTratto)
                BottoneIcona(R.drawable.ic_check, "Fatto", dimensione = 44.dp, selezionato = true, onClick = onChiudi)
            } else {
                TextButton(onApriInTratto) {
                    Icona(R.drawable.ic_fullscreen, null, dimensione = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Apri in Tratto")
                }
                Spacer(Modifier.width(4.dp))
                Button(onChiudi) { Text("Fatto") }
            }
        }
    }
}

@Composable
private fun PennaFoglietto(p: Penna, selezionata: Boolean, colore: Int, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.BottomCenter) {
        BottoneIcona(iconaPenna(p), nomePenna(p), dimensione = 44.dp, selezionato = selezionata, onClick = onClick)
        val larghezza by animateDpAsState(if (selezionata) 14.dp else 0.dp, tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), label = "tacca")
        Box(Modifier.padding(bottom = 3.dp).width(larghezza).height(3.dp).clip(CircleShape).background(Color(Pennelli.coloreEffettivo(p, colore) or (0xFF shl 24))))
    }
}

/** Il colore della penna corrente: apre e chiude il pannello dei colori. */
@Composable
private fun PallinoColore(st: StatoStrumenti, aperto: Boolean, onClick: () -> Unit) {
    val colore by animateColorAsState(Color(st.corrente.colore), tween(TrattoMotion.ENTER_MS), label = "colore")
    val interazione = remember { MutableInteractionSource() }
    val bordo by animateDpAsState(if (aperto) 2.dp else 1.dp, tween(160), label = "bordo")
    Box(
        Modifier.size(44.dp).premibile(interazione).clip(CircleShape)
            .clickable(interazione, null, onClickLabel = "Scegli il colore", onClick = onClick)
            .semantics { contentDescription = "Colore: " + StatoStrumenti.nomeColore(st.corrente.colore) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(colore)
                .border(bordo, if (aperto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
        )
    }
}

// Le stesse icone e gli stessi nomi della tavolozza dell'editor.
private fun iconaPenna(p: Penna) = when (p) {
    Penna.STILOGRAFICA -> R.drawable.ic_ink_pen
    Penna.PENNA -> R.drawable.ic_stylus
    Penna.MATITA -> R.drawable.ic_edit
    Penna.EVIDENZIATORE -> R.drawable.ic_ink_highlighter
    Penna.PENNELLO -> R.drawable.ic_brush
}

private fun nomePenna(p: Penna) = when (p) {
    Penna.STILOGRAFICA -> "Stilografica"
    Penna.PENNA -> "Penna"
    Penna.MATITA -> "Matita"
    Penna.EVIDENZIATORE -> "Evidenziatore"
    Penna.PENNELLO -> "Pennello"
}
