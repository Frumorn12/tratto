package it.frumorn.tratto.ui.editor

import android.graphics.RectF
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.editor.ModoGomma
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.Strumento
import it.frumorn.tratto.ink.Pennelli
import it.frumorn.tratto.scrittura.Trascrittore
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.Schermata
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.premibile
import it.frumorn.tratto.ui.theme.LocalTrattoColors
import it.frumorn.tratto.ui.theme.TrattoMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SchermataEditor(
    stato: StatoApp,
    destinazione: Schermata.Editor,
    sessione: SessioneEditor,
    animazione: AnimatedVisibilityScope,
) {
    val colori = LocalTrattoColors.current
    val scuro = MaterialTheme.colorScheme.background.luminanceBassa()
    LaunchedEffect(scuro) {
        sessione.applicaColori(scuro, colori.paper.toArgb(), colori.desk.toArgb(), colori.paperLine.toArgb())
    }

    // Indietro predittivo: la nota si rimpicciolisce seguendo il dito, poi torna nella sua scheda.
    val indietro = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler { eventi ->
        try {
            eventi.collect { indietro.snapTo(it.progress) }
            stato.schermata = Schermata.Libreria
        } catch (e: CancellationException) {
            scope.launch { indietro.animateTo(0f, tween(TrattoMotion.EXIT_MS)) }
            throw e
        }
    }

    // Parti dello schermo dove l'inchiostro non deve disegnare.
    val maschere = remember { mutableStateMapOf<String, RectF>() }
    LaunchedEffect(maschere.toMap()) { sessione.vista.impostaMaschera(maschere.values.toList()) }
    fun Modifier.maschera(nome: String) = onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        maschere[nome] = RectF(b.left, b.top, b.right, b.bottom)
    }

    var pannello by remember { mutableStateOf(false) }
    val forma = MaterialTheme.shapes.extraLarge

    Box(
        Modifier
            .fillMaxSize()
            .sharedBounds(
                rememberSharedContentState(destinazione.origine), animazione,
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.Crop, Alignment.TopCenter),
                clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.medium),
            )
            .graphicsLayer {
                val p = indietro.value
                scaleX = 1f - 0.1f * p; scaleY = 1f - 0.1f * p
                shape = forma
                clip = p > 0f
                alpha = 1f - 0.2f * p
            }
            .background(colori.desk),
    ) {
        AndroidView(factory = { sessione.vista }, modifier = Modifier.fillMaxSize())
        EtichettaSuggerimento(sessione.suggerimento, sessione.vista) { sessione.scriviSuggerimento() }

        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.onGloballyPositioned { sessione.vista.foglio.spazioSopra = it.size.height.toFloat() }) {
                BarraSuperiore(stato, sessione, Modifier.maschera("barra"))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Tavolozza(sessione, pannello, onPannello = { pannello = it }, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp).maschera("tavolozza"))
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = pannello,
                    enter = scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 0f)) + fadeIn(tween(160)),
                    exit = scaleOut(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)) + fadeOut(tween(TrattoMotion.EXIT_MS)),
                ) {
                    DisposableEffect(Unit) { onDispose { maschere.remove("pannello") } }
                    PannelloStrumento(sessione, onChiudi = { pannello = false }, modifier = Modifier.padding(top = 10.dp).maschera("pannello"))
                }
            }
        }

        // Barra delle azioni sulla selezione del lazo.
        AnimatedVisibility(
            visible = sessione.selezione,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
            enter = slideInVertically(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) { it } + fadeIn(),
            exit = slideOutVertically(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) { it } + fadeOut(),
        ) {
            DisposableEffect(Unit) { onDispose { maschere.remove("selezione") } }
            BarraSelezione(sessione, Modifier.maschera("selezione"))
        }

        // Pannello della trascrizione, sopra la barra della selezione.
        AnimatedVisibility(
            visible = sessione.trascrizione != null,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 124.dp),
            enter = slideInVertically(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) { it / 2 } + fadeIn(),
            exit = slideOutVertically(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) { it / 2 } + fadeOut(),
        ) {
            DisposableEffect(Unit) { onDispose { maschere.remove("trascrizione") } }
            PannelloTrascrizione(stato, sessione, Modifier.maschera("trascrizione"))
        }

        // "Incolla" quando c'e' qualcosa negli appunti e il lazo e' attivo.
        AnimatedVisibility(
            visible = !sessione.selezione && sessione.strumenti.strumento == Strumento.LAZO && sessione.haAppunti,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 28.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f),
        ) {
            Surface(onClick = { sessione.incolla() }, shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icona(R.drawable.ic_content_copy, null, dimensione = 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Incolla", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        IndicatorePagina(sessione, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(20.dp))
    }
}

private fun Color.luminanceBassa() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

@Composable
private fun BarraSuperiore(stato: StatoApp, s: SessioneEditor, modifier: Modifier) {
    val focus = LocalFocusManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confermaEliminaPagina by remember { mutableStateOf(false) }
    if (confermaEliminaPagina) {
        AlertDialog(
            onDismissRequest = { confermaEliminaPagina = false },
            title = { Text("Eliminare la pagina ${s.pagina + 1}?") },
            text = { Text("Se cambi idea puoi recuperarla con Annulla.") },
            confirmButton = { TextButton({ s.eliminaPagina(); confermaEliminaPagina = false }) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confermaEliminaPagina = false }) { Text("Annulla") } },
        )
    }
    var inCorso by remember { mutableStateOf(false) }
    fun esporta(azione: suspend (it.frumorn.tratto.data.NotaInfo) -> Unit) {
        menu = false
        scope.launch {
            inCorso = true
            try {
                val info = s.salvaEAttendi() ?: return@launch
                azione(info)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Esportazione non riuscita: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                inCorso = false
            }
        }
    }
    val salvaPdf = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) esporta { info -> it.frumorn.tratto.ui.esporta.Esporta.salvaPdf(context, stato.archivio, info, uri) } }
    var titolo by remember(s.titolo) { mutableStateOf(s.titolo) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).height(60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottoneIcona(R.drawable.ic_arrow_back, "Torna alle note") { stato.schermata = Schermata.Libreria }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (titolo.isEmpty()) Text("Senza titolo", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    BasicTextField(
                        value = titolo,
                        onValueChange = { titolo = it; s.rinomina(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BottoneIcona(R.drawable.ic_undo, "Annulla", attivo = s.puoAnnullare) { s.annulla() }
                BottoneIcona(R.drawable.ic_redo, "Ripeti", attivo = s.puoRipetere) { s.ripeti() }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                BottoneIcona(R.drawable.ic_note_add, "Aggiungi pagina") { s.aggiungiPagina() }
                BottoneIcona(R.drawable.ic_delete, "Elimina pagina", attivo = s.pagine > 1) {
                    // Una pagina vuota se ne va subito; se c'e' qualcosa si chiede (si puo' comunque annullare).
                    if (s.paginaVuota()) s.eliminaPagina() else confermaEliminaPagina = true
                }
                Box {
                    BottoneIcona(R.drawable.ic_more_vert, "Altro") { menu = true }
                    DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium) {
                        Text("SFONDO PAGINA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        listOf(Sfondo.BIANCO to "Bianca", Sfondo.RIGHE to "A righe", Sfondo.QUADRETTI to "A quadretti", Sfondo.PUNTINI to "A puntini").forEach { (sf, nome) ->
                            DropdownMenuItem(text = { Text(nome) }, onClick = { s.cambiaSfondo(sf); menu = false })
                        }
                        HorizontalDivider()
                        Text("ESPORTA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = { Text("Condividi come PDF") }, leadingIcon = { Icona(R.drawable.ic_ios_share, null) },
                            onClick = { esporta { info -> it.frumorn.tratto.ui.esporta.Esporta.condividiPdf(context, stato.archivio, info) } })
                        DropdownMenuItem(text = { Text("Salva PDF…") }, leadingIcon = { Icona(R.drawable.ic_picture_as_pdf, null) },
                            onClick = {
                                menu = false
                                val info = stato.archivio.note.value.find { it.id == s.id }
                                if (info != null) salvaPdf.launch(it.frumorn.tratto.ui.esporta.Esporta.nomePdf(info))
                            })
                        DropdownMenuItem(text = { Text("Condividi pagina come immagine") }, leadingIcon = { Icona(R.drawable.ic_image, null) },
                            onClick = {
                                val numero = s.pagina + 1
                                esporta { info -> s.paginaCorrente()?.let { p -> it.frumorn.tratto.ui.esporta.Esporta.condividiPagina(context, stato.archivio, info, p, numero) } }
                            })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Elimina questa pagina") }, enabled = s.pagine > 1,
                            leadingIcon = { Icona(R.drawable.ic_delete, null) }, onClick = { s.eliminaPagina(); menu = false })
                    }
                }
            }
            if (inCorso) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

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

/** La barra degli strumenti: penne, gomma, lazo e il colore corrente. */
@Composable
private fun Tavolozza(s: SessioneEditor, pannelloAperto: Boolean, onPannello: (Boolean) -> Unit, modifier: Modifier) {
    val st = s.strumenti
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Penna.entries.forEach { p ->
                val sel = st.strumento == Strumento.PENNA && st.penna == p
                StrumentoPenna(p, sel, st.penne.getValue(p).colore) {
                    if (sel) onPannello(!pannelloAperto)
                    else { s.cambiaStrumenti(st.copy(strumento = Strumento.PENNA, penna = p)); if (pannelloAperto) onPannello(true) }
                }
            }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BottoneIcona(R.drawable.ic_ink_eraser, "Gomma", selezionato = st.strumento == Strumento.GOMMA) {
                if (st.strumento == Strumento.GOMMA) onPannello(!pannelloAperto) else s.cambiaStrumenti(st.copy(strumento = Strumento.GOMMA))
            }
            BottoneIcona(R.drawable.ic_lasso_select, "Lazo", selezionato = st.strumento == Strumento.LAZO) {
                if (st.strumento == Strumento.LAZO) onPannello(!pannelloAperto) else s.cambiaStrumenti(st.copy(strumento = Strumento.LAZO))
            }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            // Colore e spessore correnti: apre il pannello.
            val imp = st.corrente
            val coloreAnim by animateColorAsState(Color(imp.colore), tween(TrattoMotion.ENTER_MS), label = "colore")
            val interazione = remember { MutableInteractionSource() }
            Box(
                Modifier.size(44.dp).premibile(interazione).clip(CircleShape)
                    .clickable(interazione, null) { onPannello(!pannelloAperto) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(coloreAnim).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape))
            }
        }
    }
}

@Composable
private fun StrumentoPenna(p: Penna, selezionato: Boolean, colore: Int, onClick: () -> Unit) {
    // La penna scelta "si alza" come se la si prendesse dall'astuccio.
    val alzata by animateDpAsState(if (selezionato) (-3).dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "alzata")
    Box(contentAlignment = Alignment.BottomCenter) {
        BottoneIcona(iconaPenna(p), nomePenna(p), Modifier.offset(y = alzata), selezionato = selezionato, onClick = onClick)
        val larghezza by animateDpAsState(if (selezionato) 16.dp else 0.dp, tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), label = "tacca")
        Box(Modifier.padding(bottom = 3.dp).width(larghezza).height(3.dp).clip(CircleShape).background(Color(Pennelli.coloreEffettivo(p, colore) or (0xFF shl 24))))
    }
}

/** Pannello sotto la tavolozza: colore e spessore della penna, o le opzioni di gomma e lazo. */
@Composable
private fun PannelloStrumento(s: SessioneEditor, onChiudi: () -> Unit, modifier: Modifier) {
    val st = s.strumenti
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.width(420.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            when (st.strumento) {
                Strumento.PENNA -> OpzioniPenna(s, st)
                Strumento.GOMMA -> OpzioniGomma(s, st)
                Strumento.LAZO -> {
                    Text("Lazo", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Circonda con la penna i tratti da spostare, ridimensionare, colorare o copiare. Il pallino in basso a destra ridimensiona.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onChiudi) { Text("Fatto") }
            }
        }
    }
}

@Composable
private fun OpzioniPenna(s: SessioneEditor, st: StatoStrumenti) {
    val imp = st.corrente
    val evidenziatore = st.penna == Penna.EVIDENZIATORE
    val intervallo = if (evidenziatore) StatoStrumenti.SPESSORI_EVIDENZIATORE else StatoStrumenti.SPESSORI
    fun aggiorna(colore: Int = imp.colore, spessore: Float = imp.spessore) {
        val recenti = if (colore != imp.colore) (listOf(colore) + st.coloriRecenti.filter { it != colore }).take(6) else st.coloriRecenti
        s.cambiaStrumenti(st.copy(penne = st.penne + (st.penna to imp.copy(colore = colore, spessore = spessore)), coloriRecenti = recenti))
    }
    Text(nomePenna(st.penna), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    AnteprimaTratto(st.penna, imp.colore, imp.spessore)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Spessore", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(84.dp))
        Slider(
            value = imp.spessore, onValueChange = { aggiorna(spessore = it) }, valueRange = intervallo,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text("COLORE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(10.dp))
    GrigliaColori(StatoStrumenti.TAVOLOZZA, imp.colore) { aggiorna(colore = it) }
    if (st.coloriRecenti.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("RECENTI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        GrigliaColori(st.coloriRecenti, imp.colore) { aggiorna(colore = it) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun GrigliaColori(colori: List<Int>, corrente: Int, onScelta: (Int) -> Unit) {
    // Due righe da sei: tutti i colori restano dentro il pannello.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 6,
    ) {
        colori.forEach { c ->
            val sel = c == corrente
            val bordo by animateDpAsState(if (sel) 3.dp else 1.dp, tween(160), label = "bordo")
            val interazione = remember { MutableInteractionSource() }
            Box(
                Modifier.size(32.dp).premibile(interazione).clip(CircleShape).background(Color(c))
                    .border(bordo, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                    .clickable(interazione, null, onClickLabel = StatoStrumenti.nomeColore(c)) { onScelta(c) }
                    .semantics { contentDescription = StatoStrumenti.nomeColore(c); selected = sel },
            )
        }
    }
}

/** Un'onda d'esempio che mostra come verra' il tratto, con la pressione che cresce. */
@Composable
private fun AnteprimaTratto(penna: Penna, colore: Int, spessore: Float) {
    val c = Color(Pennelli.coloreEffettivo(penna, colore))
    val animato by animateFloatAsState(spessore, tween(120), label = "spessore")
    Canvas(Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.small).background(LocalTrattoColors.current.paper)) {
        val n = 40
        val scala = size.width / 700f
        val variabile = penna != Penna.EVIDENZIATORE
        for (i in 0 until n) {
            val t0 = i / n.toFloat(); val t1 = (i + 1) / n.toFloat()
            val p0 = Offset(size.width * (0.06f + 0.88f * t0), size.height / 2 + size.height * 0.28f * kotlin.math.sin(t0 * 6.28f))
            val p1 = Offset(size.width * (0.06f + 0.88f * t1), size.height / 2 + size.height * 0.28f * kotlin.math.sin(t1 * 6.28f))
            val f = if (variabile) 0.35f + 1.1f * t0 else 1f
            drawLine(c, p0, p1, strokeWidth = (animato * scala * 1.4f * f).coerceAtLeast(1f), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun OpzioniGomma(s: SessioneEditor, st: StatoStrumenti) {
    Text("Gomma", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(ModoGomma.TRATTO to "Tratto intero", ModoGomma.PARZIALE to "Solo dove passi").forEachIndexed { i, (m, nome) ->
            SegmentedButton(
                selected = st.modoGomma == m, onClick = { s.cambiaStrumenti(st.copy(modoGomma = m)) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(nome) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Dimensione", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp))
        Slider(st.raggioGomma, { s.cambiaStrumenti(st.copy(raggioGomma = it)) }, valueRange = 6f..48f, modifier = Modifier.weight(1f))
    }
    Text("Suggerimento: tieni premuto il tasto della penna mentre scrivi per cancellare al volo.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BarraSelezione(s: SessioneEditor, modifier: Modifier) {
    var colori by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.animation.AnimatedVisibility(colori) {
                Box(Modifier.padding(8.dp)) { GrigliaColori(StatoStrumenti.TAVOLOZZA, 0) { s.vista.coloraSelezione(it); colori = false } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Nella versione libera non c'e' il riconoscimento della scrittura.
                if (Trascrittore.DISPONIBILE) AzioneSelezione(R.drawable.ic_text_fields, "Trascrivi") { s.trascrivi() }
                AzioneSelezione(R.drawable.ic_palette, "Colore") { colori = !colori }
                AzioneSelezione(R.drawable.ic_content_copy, "Copia") { s.copia() }
                AzioneSelezione(R.drawable.ic_content_cut, "Taglia") { s.taglia() }
                AzioneSelezione(R.drawable.ic_delete, "Elimina") { s.vista.eliminaSelezione() }
                AzioneSelezione(R.drawable.ic_close, "Chiudi") { s.vista.chiudiSelezione() }
            }
        }
    }
}

@Composable
private fun PannelloTrascrizione(stato: StatoApp, s: SessioneEditor, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Tiene l'ultimo stato mentre il pannello si chiude, cosi' l'animazione d'uscita non resta vuota.
    var ultimo by remember { mutableStateOf<SessioneEditor.Trascrizione>(SessioneEditor.Trascrizione.InCorso) }
    s.trascrizione?.let { ultimo = it }
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.width(560.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icona(R.drawable.ic_text_fields, null, tinta = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Trascrizione", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                BottoneIcona(R.drawable.ic_close, "Chiudi la trascrizione", dimensione = 40.dp) { s.chiudiTrascrizione() }
            }
            Spacer(Modifier.height(10.dp))
            when (val t = ultimo) {
                SessioneEditor.Trascrizione.InCorso -> {
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Leggo la tua scrittura…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SessioneEditor.Trascrizione.ServeModello -> {
                    Text("Per trascrivere serve il riconoscimento della scrittura in italiano: circa 14 MB da scaricare una volta sola. Poi funziona anche senza internet.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { s.scaricaModello() }) { Text("Scarica e trascrivi") }
                }
                is SessioneEditor.Trascrizione.Errore -> {
                    Text(t.messaggio, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { s.trascrivi() }) { Text("Riprova") }
                }
                is SessioneEditor.Trascrizione.Pronta -> {
                    var testo by remember(t.testo) { mutableStateOf(t.testo) }
                    androidx.compose.material3.OutlinedTextField(
                        testo, { testo = it }, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge, minLines = 2, maxLines = 6,
                        supportingText = { Text("Puoi correggere il testo prima di usarlo") },
                    )
                    Spacer(Modifier.height(12.dp))
                    val predefinito = stato.preferenze.stileBellaScrittura
                    val altro = if (predefinito == it.frumorn.tratto.scrittura.Stile.CORSIVO) it.frumorn.tratto.scrittura.Stile.STAMPATELLO else it.frumorn.tratto.scrittura.Stile.CORSIVO
                    fun nome(st: it.frumorn.tratto.scrittura.Stile) = if (st == it.frumorn.tratto.scrittura.Stile.CORSIVO) "corsivo" else "stampatello"
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Button(onClick = { s.riscrivi(testo, predefinito) }) { Text("Riscrivi in ${nome(predefinito)}") }
                        androidx.compose.material3.OutlinedButton(onClick = { s.riscrivi(testo, altro) }) { Text("In ${nome(altro)}") }
                        Spacer(Modifier.weight(1f))
                        BottoneIcona(R.drawable.ic_content_copy, "Copia il testo", dimensione = 44.dp) {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Trascrizione", testo))
                        }
                        BottoneIcona(R.drawable.ic_ios_share, "Condividi il testo", dimensione = 44.dp) {
                            val invio = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, testo)
                            context.startActivity(android.content.Intent.createChooser(invio, "Condividi il testo"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AzioneSelezione(icona: Int, nome: String, onClick: () -> Unit) {
    val interazione = remember { MutableInteractionSource() }
    Column(
        Modifier.premibile(interazione).clip(MaterialTheme.shapes.medium).clickable(interazione, androidx.compose.material3.ripple(), onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icona(icona, null, dimensione = 22.dp)
        Spacer(Modifier.height(2.dp))
        Text(nome, style = MaterialTheme.typography.labelSmall)
    }
}

/** "3 / 7" in basso a destra: compare mentre si scorre e poi sparisce. */
@Composable
private fun IndicatorePagina(s: SessioneEditor, modifier: Modifier) {
    var visibile by remember { mutableStateOf(false) }
    LaunchedEffect(s.ultimoScorrimento, s.pagine) {
        if (s.pagine > 1 && s.ultimoScorrimento > 0) {
            visibile = true
            delay(1400)
            visibile = false
        }
    }
    AnimatedVisibility(visibile, modifier, enter = fadeIn(tween(160)), exit = fadeOut(tween(400))) {
        Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("${s.pagina + 1} / ${s.pagine}", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

