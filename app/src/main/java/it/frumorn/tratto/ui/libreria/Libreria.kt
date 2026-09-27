package it.frumorn.tratto.ui.libreria

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Cartella
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Filtro
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.Schermata
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.premibile
import it.frumorn.tratto.ui.theme.Eyebrow
import it.frumorn.tratto.ui.theme.LocalTrattoColors
import it.frumorn.tratto.ui.theme.TrattoMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val ColoriCartelle = listOf(0xFF6546F3, 0xFF2F6BFF, 0xFF0FA3B1, 0xFF1E9E5A, 0xFFF2B705, 0xFFFF7A1A, 0xFFE0354B, 0xFF8A5A3C).map { it.toInt() }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.Libreria(stato: StatoApp, animazione: AnimatedVisibilityScope) {
    val note by stato.archivio.note.collectAsState()
    val cartelle by stato.archivio.cartelle.collectAsState()
    var selezionate by remember { mutableStateOf(emptySet<String>()) }
    var mostraNuovaCartella by remember { mutableStateOf(false) }
    var mostraSposta by remember { mutableStateOf(false) }
    var confermaElimina by remember { mutableStateOf(false) }
    var rinominaCartella by remember { mutableStateOf<Cartella?>(null) }
    val importa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) stato.importaPdf(uri) }

    val visibili = remember(note, stato.filtro, stato.ricerca) {
        val q = stato.ricerca.trim().lowercase()
        note.filter { n ->
            when (val f = stato.filtro) {
                Filtro.Tutte -> true
                Filtro.Preferite -> n.preferita
                is Filtro.InCartella -> n.cartellaId == f.id
            } && (q.isEmpty() || titoloDi(n).lowercase().contains(q))
        }
    }

    BackHandler(enabled = selezionate.isNotEmpty()) { selezionate = emptySet() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(172.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "intestazione") {
                Intestazione(stato, cartelle, selezionate, onChiudiSelezione = { selezionate = emptySet() },
                    onPreferita = { val tutte = note.filter { it.id in selezionate }.all { it.preferita }; selezionate.forEach { stato.preferita(it, !tutte) }; selezionate = emptySet() },
                    onSposta = { mostraSposta = true },
                    onElimina = { confermaElimina = true },
                    onImporta = { importa.launch(arrayOf("application/pdf")) })
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "filtri") {
                Filtri(stato, cartelle, note, onNuova = { mostraNuovaCartella = true }, onModifica = { rinominaCartella = it })
            }
            if (stato.indiceCaricato && visibili.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "vuoto") { Vuoto(stato) }
            }
            items(visibili, key = { it.id }) { n ->
                SchedaNota(
                    stato, n, selezionata = n.id in selezionate, inSelezione = selezionate.isNotEmpty(), animazione = animazione,
                    onClick = {
                        if (selezionate.isNotEmpty()) selezionate = if (n.id in selezionate) selezionate - n.id else selezionate + n.id
                        else stato.apri(n)
                    },
                    onLungo = { selezionate = selezionate + n.id },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        // Pulsante "nuova nota": l'editor si apre crescendo da qui.
        val interazione = remember { MutableInteractionSource() }
        AnimatedVisibility(
            visible = selezionate.isEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(28.dp),
            enter = scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) + fadeIn(),
            exit = scaleOut(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) + fadeOut(),
        ) {
            Surface(
                onClick = { stato.nuovaNota(origine = "nuova") },
                interactionSource = interazione,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .premibile(interazione)
                    .sharedBounds(rememberSharedContentState("nuova"), animazione, clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.large)),
            ) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icona(R.drawable.ic_edit_note, null, dimensione = 26.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Nuova nota", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }

    if (mostraNuovaCartella) DialogoCartella(null, onChiudi = { mostraNuovaCartella = false }) { nome, colore -> stato.nuovaCartella(nome, colore) }
    rinominaCartella?.let { c ->
        DialogoCartella(c, onChiudi = { rinominaCartella = null }, onElimina = { stato.eliminaCartella(c.id) }) { nome, _ -> stato.inBackground { stato.archivio.rinominaCartella(c.id, nome) } }
    }
    if (mostraSposta) {
        AlertDialog(
            onDismissRequest = { mostraSposta = false },
            title = { Text("Sposta in") },
            text = {
                Column {
                    VoceCartella("Nessuna cartella", MaterialTheme.colorScheme.outline) { stato.sposta(selezionate, null); selezionate = emptySet(); mostraSposta = false }
                    cartelle.forEach { c -> VoceCartella(c.nome, Color(c.colore)) { stato.sposta(selezionate, c.id); selezionate = emptySet(); mostraSposta = false } }
                }
            },
            confirmButton = { TextButton({ mostraSposta = false }) { Text("Annulla") } },
        )
    }
    if (confermaElimina) {
        AlertDialog(
            onDismissRequest = { confermaElimina = false },
            title = { Text(if (selezionate.size == 1) "Eliminare la nota?" else "Eliminare ${selezionate.size} note?") },
            text = { Text("Non si possono recuperare, a meno di avere un backup.") },
            confirmButton = { TextButton({ stato.elimina(selezionate); selezionate = emptySet(); confermaElimina = false }) { Text("Elimina", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confermaElimina = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun VoceCartella(nome: String, colore: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).combinedClickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(colore))
        Spacer(Modifier.width(14.dp))
        Text(nome, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Intestazione(
    stato: StatoApp,
    cartelle: List<Cartella>,
    selezionate: Set<String>,
    onChiudiSelezione: () -> Unit,
    onPreferita: () -> Unit,
    onSposta: () -> Unit,
    onElimina: () -> Unit,
    onImporta: () -> Unit,
) {
    val titolo = when (val f = stato.filtro) {
        Filtro.Tutte -> "Le tue note"
        Filtro.Preferite -> "Preferite"
        is Filtro.InCartella -> cartelle.find { it.id == f.id }?.nome ?: "Cartella"
    }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 28.dp, bottom = 8.dp)) {
        AnimatedContent(
            targetState = selezionate.isNotEmpty(),
            transitionSpec = {
                (fadeIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) + slideInVertically { -it / 3 }) togetherWith
                    (fadeOut(tween(TrattoMotion.EXIT_MS)) + slideOutVertically { it / 3 })
            },
            label = "intestazione",
        ) { inSelezione ->
            if (inSelezione) {
                Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
                    BottoneIcona(R.drawable.ic_close, "Annulla selezione", onClick = onChiudiSelezione)
                    Spacer(Modifier.width(8.dp))
                    Text("${selezionate.size} selezionate", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    BottoneIcona(R.drawable.ic_star, "Preferita", onClick = onPreferita)
                    BottoneIcona(R.drawable.ic_drive_file_move, "Sposta", onClick = onSposta)
                    BottoneIcona(R.drawable.ic_delete, "Elimina", onClick = onElimina)
                }
            } else {
                Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("TRATTO", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(titolo, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "titolo") {
                            Text(it, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Ricerca(stato)
                    BottoneIcona(R.drawable.ic_picture_as_pdf, "Importa un PDF", onClick = onImporta)
                    BottoneIcona(R.drawable.ic_settings, "Impostazioni") { stato.schermata = Schermata.Impostazioni }
                }
            }
        }
    }
}

@Composable
private fun Ricerca(stato: StatoApp) {
    var aperta by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = aperta,
            enter = expandHorizontally(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) + fadeIn(),
            exit = shrinkHorizontally(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) + fadeOut(),
        ) {
            Box(
                Modifier.width(260.dp).height(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (stato.ricerca.isEmpty()) Text("Cerca nei titoli", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = stato.ricerca, onValueChange = { stato.ricerca = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        BottoneIcona(if (aperta) R.drawable.ic_close else R.drawable.ic_search, if (aperta) "Chiudi la ricerca" else "Cerca") {
            aperta = !aperta
            if (!aperta) stato.ricerca = ""
        }
    }
}

@Composable
private fun Filtri(stato: StatoApp, cartelle: List<Cartella>, note: List<NotaInfo>, onNuova: () -> Unit, onModifica: (Cartella) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        item { Chip("Tutte · ${note.size}", stato.filtro == Filtro.Tutte, null) { stato.filtro = Filtro.Tutte } }
        item { Chip("Preferite", stato.filtro == Filtro.Preferite, null, icona = R.drawable.ic_star) { stato.filtro = Filtro.Preferite } }
        items(cartelle, key = { it.id }) { c ->
            val sel = (stato.filtro as? Filtro.InCartella)?.id == c.id
            Chip(c.nome, sel, Color(c.colore), onLungo = { onModifica(c) }) { stato.filtro = if (sel) Filtro.Tutte else Filtro.InCartella(c.id) }
        }
        item { Chip("Cartella", false, null, icona = R.drawable.ic_add, onClick = onNuova) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Chip(testo: String, selezionato: Boolean, puntino: Color?, icona: Int? = null, onLungo: (() -> Unit)? = null, onClick: () -> Unit) {
    val interazione = remember { MutableInteractionSource() }
    FilterChip(
        selected = selezionato,
        onClick = onClick,
        interactionSource = interazione,
        label = { Text(testo, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = when {
            puntino != null -> ({ Box(Modifier.size(10.dp).clip(CircleShape).background(puntino)) })
            icona != null -> ({ Icona(icona, null, dimensione = 18.dp) })
            else -> null
        },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(true, selezionato, borderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.premibile(interazione).then(
            if (onLungo != null) Modifier.combinedClickable(interactionSource = interazione, indication = null, onLongClick = onLungo, onClick = onClick) else Modifier,
        ),
    )
}

@Composable
private fun Vuoto(stato: StatoApp) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icona(R.drawable.ic_draw, null, dimensione = 56.dp, tinta = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(if (stato.ricerca.isBlank()) "Nessuna nota qui" else "Nessun risultato", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tocca «Nuova nota» oppure premi due volte il tasto della penna\ntenendola vicina allo schermo.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SharedTransitionScope.SchedaNota(
    stato: StatoApp,
    n: NotaInfo,
    selezionata: Boolean,
    inSelezione: Boolean,
    animazione: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onLungo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interazione = remember { MutableInteractionSource() }
    val colori = LocalTrattoColors.current
    val forma = MaterialTheme.shapes.medium
    // Comparsa morbida delle schede la prima volta che la libreria si apre.
    val comparsa = remember { Animatable(if (Comparse.gia) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (comparsa.value < 1f) {
            delay((Comparse.prossimo++).coerceAtMost(12) * TrattoMotion.STAGGER_MS.toLong())
            comparsa.animateTo(1f, tween(TrattoMotion.ENTER_MS + 80, easing = TrattoMotion.Enter))
        }
    }
    LaunchedEffect(Unit) { delay(900); Comparse.gia = true }
    val dy = with(LocalDensity.current) { 18.dp.toPx() }
    Column(
        modifier
            .graphicsLayer { alpha = comparsa.value; translationY = (1f - comparsa.value) * dy }
            .premibile(interazione)
            .combinedClickable(interactionSource = interazione, indication = null, onClick = onClick, onLongClick = onLungo),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1000f / 1414f)
                .sharedBounds(rememberSharedContentState("nota-${n.id}"), animazione, clipInOverlayDuringTransition = OverlayClip(forma))
                .shadow(if (selezionata) 0.dp else 2.dp, forma, spotColor = Color(0x331F1747))
                .clip(forma)
                .background(colori.paper)
                .border(
                    if (selezionata) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    forma,
                ),
        ) {
            val img = anteprima(stato, n)
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (n.conPdf) Etichetta("PDF", Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (n.preferita) Icona(R.drawable.ic_star_filled, "Preferita", Modifier.align(Alignment.TopEnd).padding(8.dp), tinta = Color(0xFFF2B705), dimensione = 20.dp)
            if (inSelezione) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp).clip(CircleShape)
                        .background(if (selezionata) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f))
                        .border(1.5.dp, if (selezionata) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (selezionata) Icona(R.drawable.ic_check, null, tinta = MaterialTheme.colorScheme.onPrimary, dimensione = 16.dp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(titoloDi(n), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            quando(n.modificata) + if (n.pagine > 1) " · ${n.pagine} pagine" else "",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

/** Le schede compaiono in sequenza solo alla prima apertura della libreria. */
private object Comparse {
    var gia = false
    var prossimo = 0
}

@Composable
private fun Etichetta(testo: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.secondary).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(testo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondary)
    }
}

private val cacheAnteprime = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
private fun anteprima(stato: StatoApp, n: NotaInfo): ImageBitmap? {
    val chiave = "${n.id}/${n.modificata}/${stato.versioneAnteprime}"
    val img by produceState(cacheAnteprime.get(chiave), chiave) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                val f: File = stato.archivio.fileAnteprima(n.id)
                if (!f.exists()) null else BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { cacheAnteprime.put(chiave, it) }
            }
        }
    }
    return img
}

fun titoloDi(n: NotaInfo): String = n.titolo.ifBlank { "Nota del " + DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN).format(Instant.ofEpochMilli(n.creata).atZone(ZoneId.systemDefault())) }

fun quando(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val oggi = LocalDate.now()
    val ora = DateTimeFormatter.ofPattern("HH:mm").format(t)
    return when (t.toLocalDate()) {
        oggi -> "Oggi, $ora"
        oggi.minusDays(1) -> "Ieri, $ora"
        else -> DateTimeFormatter.ofPattern(if (t.year == oggi.year) "d MMM" else "d MMM yyyy", Locale.ITALIAN).format(t)
    }
}

@Composable
private fun DialogoCartella(cartella: Cartella?, onChiudi: () -> Unit, onElimina: (() -> Unit)? = null, onConferma: (String, Int) -> Unit) {
    var nome by remember { mutableStateOf(cartella?.nome ?: "") }
    var colore by remember { mutableStateOf(cartella?.colore ?: ColoriCartelle.first()) }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(if (cartella == null) "Nuova cartella" else "Modifica cartella") },
        text = {
            Column {
                OutlinedTextField(nome, { nome = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (cartella == null) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ColoriCartelle.forEach { c ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                                    .border(if (c == colore) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .combinedClickable { colore = c },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ if (nome.isNotBlank()) { onConferma(nome.trim(), colore); onChiudi() } }, enabled = nome.isNotBlank()) { Text(if (cartella == null) "Crea" else "Salva") }
        },
        dismissButton = {
            Row {
                if (onElimina != null) TextButton({ onElimina(); onChiudi() }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
                TextButton(onChiudi) { Text("Annulla") }
            }
        },
    )
}
