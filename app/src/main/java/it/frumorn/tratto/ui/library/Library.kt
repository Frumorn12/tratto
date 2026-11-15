package it.frumorn.tratto.ui.library

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Capitolo
import it.frumorn.tratto.data.Cartella
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Filtro
import it.frumorn.tratto.ui.cartellaAperta
import it.frumorn.tratto.ui.capitoloAperto
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.Schermata
import it.frumorn.tratto.ui.AppState
import it.frumorn.tratto.ui.premibile
import it.frumorn.tratto.ui.windowInfo
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

/** Cible de dépôt d'un chapitre du cahier ouvert : "cap:" suivi de l'id, ou rien pour « sans chapitre ». */
private const val CIBLE_CAPITOLO = "cap:"

val ColoriCartelle = listOf(0xFF6546F3, 0xFF2F6BFF, 0xFF0FA3B1, 0xFF1E9E5A, 0xFFF2B705, 0xFFFF7A1A, 0xFFE0354B, 0xFF8A5A3C).map { it.toInt() }

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun SharedTransitionScope.Library(stato: AppState, animazione: AnimatedVisibilityScope) {
    val note by stato.archivio.note.collectAsState()
    val cartelle by stato.archivio.cartelle.collectAsState()
    var selezionate by remember { mutableStateOf(emptySet<String>()) }
    var mostraNuovaCartella by remember { mutableStateOf(false) }
    var mostraSposta by remember { mutableStateOf(false) }
    var confermaElimina by remember { mutableStateOf(false) }
    var rinominaCartella by remember { mutableStateOf<Cartella?>(null) }
    /** Quaderno e capitolo da modificare; capitolo null per crearne uno. */
    var dialogoCapitolo by remember { mutableStateOf<Pair<String, Capitolo?>?>(null) }
    var chiediOverlay by remember { mutableStateOf(false) }
    var glissement by remember { mutableStateOf<Glissement?>(null) }
    val zones = remember { mutableStateMapOf<String, Rect>() }
    var origine by remember { mutableStateOf(Offset.Zero) }
    var retourFiltre by remember { mutableStateOf<Filtro>(Filtro.Tutte) }
    var ouverture by remember { mutableStateOf<String?>(null) }
    var ouvertureChapitre by remember { mutableStateOf<String?>(null) }
    val vue = LocalView.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val importa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        HomeButton.cache(context)
        if (uri != null) stato.importaPdf(uri)
    }
    fun apriSelettore() {
        if (Settings.canDrawOverlays(context) && HomeButton.montre(context)) {
            importa.launch(arrayOf("application/pdf"))
        } else {
            chiediOverlay = true
        }
    }

    fun sortir() {
        when (val f = stato.filtro) {
            is Filtro.InCapitolo -> {
                ouvertureChapitre = null
                stato.filtro = Filtro.InCartella(f.cartellaId)
            }
            is Filtro.InCartella -> {
                ouverture = null
                ouvertureChapitre = null
                stato.filtro = retourFiltre
            }
            else -> Unit
        }
    }

    BackHandler(enabled = stato.filtro.cartellaAperta() != null && selezionate.isEmpty() && glissement == null) { sortir() }
    BackHandler(enabled = selezionate.isNotEmpty() && glissement == null) { selezionate = emptySet() }

    fun poseZone(cle: String, rect: Rect) {
        zones[cle] = rect
        glissement?.let { g -> glissement = g.copy(cible = zoneSous(zones, g.x, g.y)) }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).origineRacine { origine = it }) {
        // Entrée dans un cahier / chapitre : même animation, contenu filtré.
        AnimatedContent(
            targetState = when (val f = stato.filtro) {
                is Filtro.InCapitolo -> "${f.cartellaId}/${f.capitoloId}"
                is Filtro.InCartella -> f.id
                else -> null
            },
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val from = initialState
                val to = targetState
                val versInterieur = to != null && (from == null || (!from.contains('/') && to.contains('/')))
                val horsInterieur = to == null || (from != null && from.contains('/') && to != null && !to.contains('/'))
                val origine = TransformOrigin(0.5f, 0.16f)
                when {
                    versInterieur -> (
                        fadeIn(tween(TrattoMotion.ENTER_MS, delayMillis = 60)) +
                            scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.84f, transformOrigin = origine) +
                            slideInVertically(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) { it / 14 }
                        ) togetherWith fadeOut(tween(TrattoMotion.EXIT_MS))
                    horsInterieur -> fadeIn(tween(TrattoMotion.ENTER_MS, delayMillis = 40)) togetherWith (
                        fadeOut(tween(TrattoMotion.EXIT_MS)) +
                            scaleOut(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit), targetScale = 0.88f, transformOrigin = origine) +
                            slideOutVertically(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) { -it / 16 }
                        )
                    else -> fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                }
            },
            label = "entree-cahier",
        ) { cleOuverte ->
            val grilleState = rememberLazyGridState()
            var grille by remember { mutableStateOf(Rect.Zero) }
            val filtre = when {
                cleOuverte == null -> retourFiltre
                '/' in cleOuverte -> Filtro.InCapitolo(cleOuverte.substringBefore('/'), cleOuverte.substringAfter('/'))
                else -> Filtro.InCartella(cleOuverte)
            }
            val cahierId = filtre.cartellaAperta()
            val chapitreId = filtre.capitoloAperto()
            val cahier = cahierId?.let { id -> cartelle.find { it.id == id } }
            val idCapitoli = cahier?.capitoli?.mapTo(HashSet()) { it.id }.orEmpty()
            val visibili = remember(note, filtre, stato.ricerca, idCapitoli) {
                val q = stato.ricerca.trim().lowercase()
                note.filter { n ->
                    when (filtre) {
                        Filtro.Tutte -> n.cartellaId == null
                        Filtro.Preferite -> n.preferita && n.cartellaId == null
                        // Dans le cahier : uniquement les notes hors chapitre (les autres sont dans leur chapitre).
                        is Filtro.InCartella -> n.cartellaId == filtre.id && (n.capitoloId == null || n.capitoloId !in idCapitoli)
                        is Filtro.InCapitolo -> n.cartellaId == filtre.cartellaId && n.capitoloId == filtre.capitoloId
                    } && (q.isEmpty() || titoloDi(n).lowercase().contains(q))
                }
            }
            LaunchedEffect(glissement != null) {
                if (glissement == null) return@LaunchedEffect
                val seuil = with(density) { 72.dp.toPx() }
                while (glissement != null) {
                    val g = glissement ?: break
                    val pas = when {
                        g.cible != null -> 0f
                        g.y > grille.bottom - seuil -> 42f
                        g.y < grille.top + seuil -> -42f
                        else -> 0f
                    }
                    if (pas != 0f) grilleState.scrollBy(pas)
                    delay(16)
                }
            }
            val carta: @Composable LazyGridItemScope.(NotaInfo) -> Unit = { n ->
                SchedaNota(
                    stato, n, selezionata = n.id in selezionate, inSelezione = selezionate.isNotEmpty(),
                    souleve = glissement?.ids?.contains(n.id) == true, animazione = animazione,
                    onClick = {
                        if (glissement != null) return@SchedaNota
                        if (selezionate.isNotEmpty()) selezionate = if (n.id in selezionate) selezionate - n.id else selezionate + n.id
                        else stato.apri(n)
                    },
                    onSouleve = { pos ->
                        vue.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        val ids = if (n.id in selezionate && selezionate.isNotEmpty()) selezionate else setOf(n.id)
                        val titre = if (ids.size > 1) testoOra(R.string.notes, ids.size) else titoloDi(n)
                        glissement = Glissement(ids, titre, pos.x, pos.y, zoneSous(zones, pos.x, pos.y))
                        selezionate = selezionate + n.id
                    },
                    onBouge = { pos ->
                        glissement = glissement?.copy(x = pos.x, y = pos.y, cible = zoneSous(zones, pos.x, pos.y))
                    },
                    onLache = {
                        val g = glissement
                        glissement = null
                        val cible = g?.cible
                        if (g != null && cible != null) {
                            if (cible.startsWith(CIBLE_CAPITOLO)) {
                                val dans = cahierId
                                if (dans != null) stato.spostaInCapitolo(g.ids, dans, cible.removePrefix(CIBLE_CAPITOLO).ifEmpty { null })
                            } else {
                                stato.sposta(g.ids, cible)
                            }
                            selezionate = emptySet()
                        }
                    },
                    onAnnule = { glissement = null },
                    modifier = Modifier.animateItem(),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (windowInfo().etroit) 148.dp else 172.dp),
                state = grilleState,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { grille = it.boundsInRoot() },
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "intestazione") {
                    Intestazione(stato, cartelle, filtre, selezionate, onChiudiSelezione = { selezionate = emptySet() },
                        onPreferita = { val tutte = note.filter { it.id in selezionate }.all { it.preferita }; selezionate.forEach { stato.preferita(it, !tutte) }; selezionate = emptySet() },
                        onSposta = { mostraSposta = true },
                        onElimina = { confermaElimina = true },
                        onImporta = { apriSelettore() },
                        onSortie = ::sortir)
                }
                if (cahierId == null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "filtri") {
                        Filtri(retourFiltre, note) {
                            retourFiltre = it
                            stato.filtro = it
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "cahiers") {
                        Etagere(
                            cartelle, note,
                            survole = glissement?.cible,
                            enOuverture = ouverture,
                            onZone = ::poseZone,
                            onZoneFin = { zones.remove(it) },
                            onOuvre = { c ->
                                if (stato.filtro.cartellaAperta() == null) retourFiltre = stato.filtro
                                ouverture = c.id
                                ouvertureChapitre = null
                                selezionate = emptySet()
                                stato.filtro = Filtro.InCartella(c.id)
                            },
                            onNuova = { mostraNuovaCartella = true },
                            onModifica = { rinominaCartella = it },
                        )
                    }
                }
                // Niveau cahier : étagère des chapitres (comme les cahiers à la racine).
                if (cahier != null && chapitreId == null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "chapitres") {
                        EtagereChapitres(
                            capitoli = cahier.capitoli,
                            note = note.filter { it.cartellaId == cahier.id },
                            colore = cahier.colore,
                            survole = glissement?.cible,
                            enOuverture = ouvertureChapitre,
                            onZone = ::poseZone,
                            onZoneFin = { zones.remove(it) },
                            onOuvre = { k ->
                                ouvertureChapitre = k.id
                                selezionate = emptySet()
                                stato.filtro = Filtro.InCapitolo(cahier.id, k.id)
                            },
                            onNuova = { dialogoCapitolo = cahier.id to null },
                            onModifica = { dialogoCapitolo = cahier.id to it },
                        )
                    }
                }
                if (stato.indiceCaricato && visibili.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "vuoto") {
                        Vuoto(stato, dansCahier = cahierId != null)
                    }
                }
                items(visibili, key = { it.id }) { carta(it) }
            }
        }

        glissement?.let { g ->
            val cahierCourant = stato.filtro.cartellaAperta()?.let { id -> cartelle.find { it.id == id } }
            if (cahierCourant != null && cahierCourant.capitoli.isNotEmpty()) {
                BarreDepotChapitres(
                    cartella = cahierCourant,
                    note = note.filter { it.cartellaId == cahierCourant.id },
                    survole = g.cible,
                    onZone = ::poseZone,
                    onZoneFin = { zones.remove(it) },
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                )
            } else if (cartelle.isNotEmpty()) {
                BarreDepot(
                    cartelle,
                    comptes = note.groupingBy { it.cartellaId }.eachCount().mapNotNull { (id, n) -> id?.let { it to n } }.toMap(),
                    survole = g.cible,
                    onZone = ::poseZone,
                    onZoneFin = { zones.remove(it) },
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = LocalTrattoColors.current.paper,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (g.x - origine.x).roundToInt() - 72.dp.roundToPx(),
                            (g.y - origine.y).roundToInt() - 88.dp.roundToPx(),
                        )
                    }
                    .width(144.dp),
            ) {
                Text(
                    g.titre,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                    Text(testo(R.string.new_note), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }

    if (mostraNuovaCartella) DialogoCartella(null, onChiudi = { mostraNuovaCartella = false }) { nome, colore -> stato.nuovaCartella(nome, colore) }
    rinominaCartella?.let { c ->
        DialogoCartella(c, onChiudi = { rinominaCartella = null }, onElimina = { stato.eliminaCartella(c.id) }) { nome, colore ->
            stato.modificaCartella(c.id, nome, colore)
        }
    }
    dialogoCapitolo?.let { (cahierId, k) ->
        val cahier = cartelle.find { it.id == cahierId }
        DialogoCapitolo(
            capitolo = k,
            coloreCahier = cahier?.colore ?: ColoriCartelle.first(),
            onChiudi = { dialogoCapitolo = null },
            onElimina = k?.let { { stato.eliminaCapitolo(cahierId, it.id) } },
        ) { nome, colore ->
            if (k == null) stato.nuovoCapitolo(cahierId, nome, colore)
            else stato.modificaCapitolo(cahierId, k.id, nome, colore)
        }
    }
    if (mostraSposta) {
        val aperto = stato.filtro.cartellaAperta()?.let { id -> cartelle.find { it.id == id } }
        AlertDialog(
            onDismissRequest = { mostraSposta = false },
            title = { Text(testo(R.string.move_to)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (aperto != null && aperto.capitoli.isNotEmpty()) {
                        Text(testo(R.string.chapters), style = Eyebrow, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                        VoceCartella(testo(R.string.no_chapter), MaterialTheme.colorScheme.outline) { stato.spostaInCapitolo(selezionate, aperto.id, null); selezionate = emptySet(); mostraSposta = false }
                        aperto.capitoli.forEach { k -> VoceCartella(k.nome, Color(k.colore ?: aperto.colore)) { stato.spostaInCapitolo(selezionate, aperto.id, k.id); selezionate = emptySet(); mostraSposta = false } }
                        Spacer(Modifier.height(8.dp))
                        Text(testo(R.string.notebooks), style = Eyebrow, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                    }
                    VoceCartella(testo(R.string.no_folder), MaterialTheme.colorScheme.outline) { stato.sposta(selezionate, null); selezionate = emptySet(); mostraSposta = false }
                    cartelle.forEach { c -> VoceCartella(c.nome, Color(c.colore)) { stato.sposta(selezionate, c.id); selezionate = emptySet(); mostraSposta = false } }
                }
            },
            confirmButton = { TextButton({ mostraSposta = false }) { Text(testo(R.string.cancel)) } },
        )
    }
    if (chiediOverlay) {
        AlertDialog(
            onDismissRequest = { chiediOverlay = false },
            title = { Text(testo(R.string.back_to_home_2)) },
            text = { Text(testo(R.string.the_file_screen_opens_full_screen_with_no_button)) },
            confirmButton = {
                TextButton({
                    chiediOverlay = false
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) { Text(testo(R.string.allow)) }
            },
            dismissButton = { TextButton({ chiediOverlay = false }) { Text(testo(R.string.cancel)) } },
        )
    }
    if (confermaElimina) {
        AlertDialog(
            onDismissRequest = { confermaElimina = false },
            title = { Text(if (selezionate.size == 1) testo(R.string.delete_the_note) else testo(R.string.delete_notes, selezionate.size)) },
            text = { Text(testo(R.string.they_cannot_be_recovered_unless_you_have_a_backu)) },
            confirmButton = { TextButton({ stato.elimina(selezionate); selezionate = emptySet(); confermaElimina = false }) { Text(testo(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confermaElimina = false }) { Text(testo(R.string.cancel)) } },
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
    stato: AppState,
    cartelle: List<Cartella>,
    filtre: Filtro,
    selezionate: Set<String>,
    onChiudiSelezione: () -> Unit,
    onPreferita: () -> Unit,
    onSposta: () -> Unit,
    onElimina: () -> Unit,
    onImporta: () -> Unit,
    onSortie: () -> Unit,
) {
    val titolo = when (filtre) {
        Filtro.Tutte -> testo(R.string.your_notes)
        Filtro.Preferite -> testo(R.string.favorites)
        is Filtro.InCartella -> cartelle.find { it.id == filtre.id }?.nome ?: testo(R.string.folder)
        is Filtro.InCapitolo -> cartelle.find { it.id == filtre.cartellaId }?.capitoli?.find { it.id == filtre.capitoloId }?.nome
            ?: testo(R.string.chapter_2)
    }
    val eyebrow = when (filtre) {
        is Filtro.InCapitolo -> testo(R.string.chapter)
        is Filtro.InCartella -> testo(R.string.notebook)
        else -> "TRATTO"
    }
    val aInterieur = filtre.cartellaAperta() != null
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
                    BottoneIcona(R.drawable.ic_close, testo(R.string.clear_selection), onClick = onChiudiSelezione)
                    Spacer(Modifier.width(8.dp))
                    Text(testo(R.string.selected, selezionate.size), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    BottoneIcona(R.drawable.ic_star, testo(R.string.favorite), onClick = onPreferita)
                    BottoneIcona(R.drawable.ic_drive_file_move, testo(R.string.move), onClick = onSposta)
                    BottoneIcona(R.drawable.ic_delete, testo(R.string.delete), onClick = onElimina)
                }
            } else {
                Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (aInterieur) {
                        BottoneIcona(R.drawable.ic_arrow_back, testo(R.string.back), onClick = onSortie)
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(eyebrow, style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        AnimatedContent(titolo, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "titolo") {
                            Text(it, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Ricerca(stato)
                    BottoneIcona(R.drawable.ic_picture_as_pdf, testo(R.string.import_a_pdf), onClick = onImporta)
                    BottoneIcona(R.drawable.ic_settings, testo(R.string.settings)) { stato.schermata = Schermata.Settings }
                }
            }
        }
    }
}

@Composable
private fun Ricerca(stato: AppState) {
    var aperta by rememberSaveable { mutableStateOf(false) }
    val largeurChamp = minOf(260, (windowInfo().largeurDp * 0.42f).toInt().coerceAtLeast(140)).dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            visible = aperta,
            enter = expandHorizontally(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) + fadeIn(),
            exit = shrinkHorizontally(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) + fadeOut(),
        ) {
            Box(
                Modifier.width(largeurChamp).height(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (stato.ricerca.isEmpty()) Text(testo(R.string.search_titles), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = stato.ricerca, onValueChange = { stato.ricerca = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        BottoneIcona(if (aperta) R.drawable.ic_close else R.drawable.ic_search, if (aperta) testo(R.string.close_search) else testo(R.string.search)) {
            aperta = !aperta
            if (!aperta) stato.ricerca = ""
        }
    }
}

@Composable
private fun Filtri(filtre: Filtro, note: List<NotaInfo>, onChange: (Filtro) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        val aLaRacine = note.count { it.cartellaId == null }
        item { Chip(testo(R.string.all_alaracine, aLaRacine), filtre == Filtro.Tutte, null) { onChange(Filtro.Tutte) } }
        item { Chip(testo(R.string.favorites), filtre == Filtro.Preferite, null, icona = R.drawable.ic_star) { onChange(Filtro.Preferite) } }
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
private fun Vuoto(stato: AppState, dansCahier: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icona(R.drawable.ic_draw, null, dimensione = 56.dp, tinta = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                stato.ricerca.isNotBlank() -> testo(R.string.no_results)
                dansCahier -> testo(R.string.this_notebook_is_empty)
                else -> testo(R.string.no_notes_here)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (dansCahier) testo(R.string.tap_new_note_to_write_in_this_notebook)
            else testo(R.string.tap_new_note_or_double_press_the_stylus_button_n),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun SharedTransitionScope.SchedaNota(
    stato: AppState,
    n: NotaInfo,
    selezionata: Boolean,
    inSelezione: Boolean,
    souleve: Boolean,
    animazione: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onSouleve: (Offset) -> Unit,
    onBouge: (Offset) -> Unit,
    onLache: () -> Unit,
    onAnnule: () -> Unit,
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
            .souleverNote(onSouleve, onBouge, onLache, onAnnule)
            .graphicsLayer { alpha = comparsa.value * if (souleve) 0.35f else 1f; translationY = (1f - comparsa.value) * dy }
            .premibile(interazione)
            .combinedClickable(interactionSource = interazione, indication = null, onClick = onClick),
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
            if (n.preferita) {
                Icona(
                    R.drawable.ic_star_filled,
                    testo(R.string.favorite),
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    tinta = Color(0xFFF2B705),
                    dimensione = 20.dp,
                )
            }
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
            quando(n.modificata) + if (n.pagine > 1) " · ${n.pagine} " + testo(R.string.pages_3) else "",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

@Composable
private fun DialogoCapitolo(
    capitolo: Capitolo?,
    coloreCahier: Int,
    onChiudi: () -> Unit,
    onElimina: (() -> Unit)? = null,
    onConferma: (String, Int?) -> Unit,
) {
    var nome by remember { mutableStateOf(capitolo?.nome ?: "") }
    var colore by remember { mutableStateOf(capitolo?.colore ?: coloreCahier) }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(if (capitolo == null) testo(R.string.new_chapter) else testo(R.string.edit_chapter)) },
        text = {
            Column {
                OutlinedTextField(nome, { nome = it }, label = { Text(testo(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                SceltaColore(colore) { colore = it }
            }
        },
        confirmButton = {
            TextButton(
                {
                    if (nome.isNotBlank()) {
                        onConferma(nome.trim(), colore)
                        onChiudi()
                    }
                },
                enabled = nome.isNotBlank(),
            ) {
                Text(if (capitolo == null) testo(R.string.create) else testo(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (onElimina != null) TextButton({ onElimina(); onChiudi() }) { Text(testo(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onChiudi) { Text(testo(R.string.cancel)) }
            }
        },
    )
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
private fun anteprima(stato: AppState, n: NotaInfo): ImageBitmap? {
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

fun titoloDi(n: NotaInfo): String = n.titolo.ifBlank {
    val data = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(n.creata).atZone(ZoneId.systemDefault()))
    testoOra(R.string.note_data, data)
}

fun quando(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val oggi = LocalDate.now()
    val ora = DateTimeFormatter.ofPattern("HH:mm").format(t)
    return when (t.toLocalDate()) {
        oggi -> testoOra(R.string.today_ora, ora)
        oggi.minusDays(1) -> testoOra(R.string.yesterday_ora, ora)
        else -> DateTimeFormatter.ofPattern(if (t.year == oggi.year) "d MMM" else "d MMM yyyy", Locale.getDefault()).format(t)
    }
}

@Composable
private fun SceltaColore(colore: Int, onColore: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColoriCartelle.forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (c == colore) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .combinedClickable { onColore(c) },
            )
        }
    }
}

@Composable
private fun DialogoCartella(cartella: Cartella?, onChiudi: () -> Unit, onElimina: (() -> Unit)? = null, onConferma: (String, Int) -> Unit) {
    var nome by remember { mutableStateOf(cartella?.nome ?: "") }
    var colore by remember { mutableStateOf(cartella?.colore ?: ColoriCartelle.first()) }
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(if (cartella == null) testo(R.string.new_notebook) else testo(R.string.edit_notebook)) },
        text = {
            Column {
                OutlinedTextField(nome, { nome = it }, label = { Text(testo(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                SceltaColore(colore) { colore = it }
            }
        },
        confirmButton = {
            TextButton({ if (nome.isNotBlank()) { onConferma(nome.trim(), colore); onChiudi() } }, enabled = nome.isNotBlank()) { Text(if (cartella == null) testo(R.string.create) else testo(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (onElimina != null) TextButton({ onElimina(); onChiudi() }) { Text(testo(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onChiudi) { Text(testo(R.string.cancel)) }
            }
        },
    )
}
