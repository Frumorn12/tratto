package it.frumorn.tratto.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Capitolo
import it.frumorn.tratto.data.Cartella
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.theme.Eyebrow
import it.frumorn.tratto.ui.theme.LocalTrattoColors

/** Étagère des cahiers : chaque dossier est une couverture, pas une pastille. */
@Composable
internal fun Etagere(
    cartelle: List<Cartella>,
    note: List<NotaInfo>,
    survole: String?,
    enOuverture: String?,
    onZone: (String, Rect) -> Unit,
    onZoneFin: (String) -> Unit,
    onOuvre: (Cartella) -> Unit,
    onNuova: () -> Unit,
    onModifica: (Cartella) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(testo(R.string.notebooks), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 8.dp, bottom = 8.dp)) {
            items(cartelle, key = { it.id }) { c ->
                Cahier(
                    c,
                    compte = note.count { it.cartellaId == c.id },
                    enOuverture = enOuverture == c.id,
                    attenue = enOuverture != null && enOuverture != c.id,
                    survole = survole == c.id,
                    onZone = { onZone("liste|${c.id}", it) },
                    onZoneFin = { onZoneFin("liste|${c.id}") },
                    onClick = { onOuvre(c) },
                    onLungo = { onModifica(c) },
                )
            }
            item(key = "nouveau") { NouveauCahier(onNuova) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cahier(
    cartella: Cartella,
    compte: Int,
    enOuverture: Boolean,
    attenue: Boolean,
    survole: Boolean,
    onZone: (Rect) -> Unit,
    onZoneFin: () -> Unit,
    onClick: () -> Unit,
    onLungo: () -> Unit,
) {
    DisposableEffect(cartella.id) { onDispose { onZoneFin() } }
    CouvertureLivre(
        titolo = cartella.nome,
        compte = compte,
        colore = Color(cartella.colore),
        enOuverture = enOuverture,
        attenue = attenue,
        survole = survole,
        onZone = onZone,
        onClick = onClick,
        onLungo = onLungo,
    )
}

@Composable
private fun NouveauCahier(onClick: () -> Unit) {
    NouveauLivre(
        etichetta = testo(R.string.new_notebook),
        onClick = onClick,
    )
}

/**
 * Étagère des chapitres dans un cahier ouvert : même geste que les cahiers
 * (tap pour entrer, long-appui pour modifier, dépôt par glisser-déposer).
 */
@Composable
internal fun EtagereChapitres(
    capitoli: List<Capitolo>,
    note: List<NotaInfo>,
    colore: Int,
    survole: String?,
    enOuverture: String?,
    onZone: (String, Rect) -> Unit,
    onZoneFin: (String) -> Unit,
    onOuvre: (Capitolo) -> Unit,
    onNuova: () -> Unit,
    onModifica: (Capitolo) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(testo(R.string.chapters), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(end = 8.dp, bottom = 8.dp)) {
            items(capitoli, key = { it.id }) { k ->
                val cleDepot = "cap:${k.id}"
                CahierChapitre(
                    id = k.id,
                    nome = k.nome,
                    compte = note.count { it.capitoloId == k.id },
                    colore = k.colore ?: colore,
                    enOuverture = enOuverture == k.id,
                    attenue = enOuverture != null && enOuverture != k.id,
                    survole = survole == cleDepot,
                    onZone = { onZone("liste|$cleDepot", it) },
                    onZoneFin = { onZoneFin("liste|$cleDepot") },
                    onClick = { onOuvre(k) },
                    onLungo = { onModifica(k) },
                )
            }
            item(key = "nouveau-chapitre") { NouveauChapitre(onNuova) }
        }
    }
}

@Composable
private fun CahierChapitre(
    id: String,
    nome: String,
    compte: Int,
    colore: Int,
    enOuverture: Boolean,
    attenue: Boolean,
    survole: Boolean,
    onZone: (Rect) -> Unit,
    onZoneFin: () -> Unit,
    onClick: () -> Unit,
    onLungo: () -> Unit,
) {
    DisposableEffect(id) { onDispose { onZoneFin() } }
    CouvertureChapitre(
        titolo = nome,
        compte = compte,
        colore = Color(colore),
        enOuverture = enOuverture,
        attenue = attenue,
        survole = survole,
        onZone = onZone,
        onClick = onClick,
        onLungo = onLungo,
    )
}

@Composable
private fun NouveauChapitre(onClick: () -> Unit) {
    val forme = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .padding(top = 10.dp)
            .width(118.dp)
            .height(128.dp)
            .clip(forme)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, forme)
            .combinedClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icona(R.drawable.ic_add, testo(R.string.new_chapter), dimensione = 28.dp, tinta = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(testo(R.string.action_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Couverture de chapitre : dossier à onglet (pas un livre relié).
 * Bandeau coloré en haut, corps papier, fine languette.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CouvertureChapitre(
    titolo: String,
    compte: Int,
    colore: Color,
    enOuverture: Boolean,
    attenue: Boolean,
    survole: Boolean,
    onZone: (Rect) -> Unit,
    onClick: () -> Unit,
    onLungo: () -> Unit,
) {
    val papier = LocalTrattoColors.current.paper
    val forme = RoundedCornerShape(10.dp)
    val testoSurBandeau = if (colore.luminance() > 0.62f) Color(0xFF271E52) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(118.dp)) {
        Box(
            Modifier
                .width(118.dp)
                .height(138.dp)
                .onGloballyPositioned { onZone(it.boundsInRoot()) }
                .graphicsLayer {
                    val s = when {
                        survole -> 1.08f
                        enOuverture -> 1.14f
                        else -> 1f
                    }
                    scaleX = s
                    scaleY = s
                    alpha = if (attenue) 0.35f else 1f
                }
                .combinedClickable(onClick = onClick, onLongClick = onLungo),
        ) {
            // Feuille du dessous (pile).
            Box(
                Modifier
                    .matchParentSize()
                    .padding(start = 6.dp, top = 10.dp)
                    .offset(x = 4.dp, y = 3.dp)
                    .clip(forme)
                    .background(papier.copy(alpha = 0.85f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), forme),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, end = if (enOuverture) 6.dp else 0.dp)
                    .shadow(if (enOuverture || survole) 8.dp else 2.dp, forme, spotColor = Color(0x551F1747))
                    .clip(forme)
                    .background(papier)
                    .then(if (enOuverture || survole) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forme) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, forme)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(colore)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        titolo,
                        style = MaterialTheme.typography.titleSmall,
                        color = testoSurBandeau,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Languette / filet sous le bandeau.
                Box(Modifier.fillMaxWidth().height(3.dp).background(colore.assombrie(0.82f)))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp).fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        when (compte) {
                            0 -> testo(R.string.empty)
                            1 -> testo(R.string.n_1_note)
                            else -> testo(R.string.compte_notes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF271E52).copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** Couverture de livre : dos, pages qui dépassent, étiquette — cahiers et chapitres. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CouvertureLivre(
    titolo: String,
    compte: Int,
    colore: Color,
    enOuverture: Boolean,
    attenue: Boolean,
    survole: Boolean,
    onZone: (Rect) -> Unit,
    onClick: () -> Unit,
    onLungo: () -> Unit,
) {
    val papier = LocalTrattoColors.current.paper
    val dos = colore.assombrie()
    val forme = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    val pages = RoundedCornerShape(2.dp)
    val testoSurCouverture = if (colore.luminance() > 0.62f) Color(0xFF271E52) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(128.dp)) {
        Box(
            Modifier
                .width(128.dp)
                .height(156.dp)
                .onGloballyPositioned { onZone(it.boundsInRoot()) }
                .graphicsLayer {
                    val s = when {
                        survole -> 1.08f
                        enOuverture -> 1.16f
                        else -> 1f
                    }
                    scaleX = s
                    scaleY = s
                    alpha = if (attenue) 0.35f else 1f
                }
                .combinedClickable(onClick = onClick, onLongClick = onLungo),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, top = 8.dp)
                    .offset(x = if (enOuverture) 10.dp else 5.dp, y = 2.dp)
                    .clip(pages)
                    .background(papier)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, pages),
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(end = if (enOuverture) 14.dp else 8.dp)
                    .shadow(if (enOuverture || survole) 10.dp else 3.dp, forme, spotColor = Color(0x551F1747))
                    .clip(forme)
                    .background(colore)
                    .then(if (enOuverture || survole) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forme) else Modifier),
            ) {
                Box(Modifier.width(12.dp).fillMaxHeight().background(dos))
                Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 14.dp, bottom = 12.dp).fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            titolo,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFF271E52),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        when (compte) {
                            0 -> testo(R.string.empty)
                            1 -> testo(R.string.n_1_note)
                            else -> testo(R.string.compte_notes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = testoSurCouverture,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NouveauLivre(etichetta: String, onClick: () -> Unit) {
    val forme = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    Column(
        Modifier
            .padding(top = 8.dp)
            .width(112.dp)
            .height(148.dp)
            .clip(forme)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, forme)
            .combinedClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icona(R.drawable.ic_add, etichetta, dimensione = 28.dp, tinta = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(testo(R.string.action_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Barre de dépôt vers les chapitres du cahier ouvert (glisser depuis l'intérieur). */
@Composable
internal fun BarreDepotChapitres(
    cartella: Cartella,
    note: List<NotaInfo>,
    survole: String?,
    onZone: (String, Rect) -> Unit,
    onZoneFin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SurfaceBarre(modifier) {
        Text(
            testo(R.string.drop_into_a_chapter),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            item(key = "sans") {
                val cle = "barre|cap:"
                DisposableEffect("sans") { onDispose { onZoneFin(cle) } }
                val compte = note.count { it.cartellaId == cartella.id && it.capitoloId == null }
                ColonneDepot(
                    cle = cle,
                    nome = testo(R.string.no_chapter),
                    compte = compte,
                    colore = MaterialTheme.colorScheme.outlineVariant,
                    actif = survole == "cap:",
                    onZone = onZone,
                )
            }
            items(cartella.capitoli, key = { it.id }) { k ->
                val cle = "barre|cap:${k.id}"
                DisposableEffect(k.id) { onDispose { onZoneFin(cle) } }
                ColonneDepot(
                    cle = cle,
                    nome = k.nome,
                    compte = note.count { it.capitoloId == k.id },
                    colore = Color(k.colore ?: cartella.colore),
                    actif = survole == "cap:${k.id}",
                    onZone = onZone,
                )
            }
        }
    }
}

@Composable
private fun ColonneDepot(
    cle: String,
    nome: String,
    compte: Int,
    colore: Color,
    actif: Boolean,
    onZone: (String, Rect) -> Unit,
) {
    val forme = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)
    val dos = colore.assombrie()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Box(
            Modifier
                .width(88.dp)
                .height(72.dp)
                .onGloballyPositioned { onZone(cle, it.boundsInRoot()) }
                .graphicsLayer {
                    val s = if (actif) 1.08f else 1f
                    scaleX = s
                    scaleY = s
                }
                .shadow(if (actif) 6.dp else 2.dp, forme, spotColor = Color(0x551F1747))
                .clip(forme)
                .background(colore)
                .then(if (actif) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, forme) else Modifier),
        ) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(8.dp).fillMaxHeight().background(dos))
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when (compte) {
                            0 -> testo(R.string.empty)
                            1 -> testo(R.string.n_1_note)
                            else -> testo(R.string.compte_notes)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (colore.luminance() > 0.62f) Color(0xFF271E52) else Color.White,
                    )
                }
            }
        }
        Text(nome, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Les cahiers restent sous le doigt pendant le glisser, même si l'étagère a défilé hors de l'écran. */
@Composable
internal fun BarreDepot(
    cartelle: List<Cartella>,
    comptes: Map<String, Int>,
    survole: String?,
    onZone: (String, Rect) -> Unit,
    onZoneFin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SurfaceBarre(modifier) {
        Text(
            testo(R.string.drop_into_a_notebook),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            items(cartelle, key = { it.id }) { c ->
                val cle = "barre|${c.id}"
                DisposableEffect(c.id) { onDispose { onZoneFin(cle) } }
                val compte = comptes[c.id] ?: 0
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
                    Box(
                        Modifier
                            .width(88.dp)
                            .height(64.dp)
                            .onGloballyPositioned { onZone(cle, it.boundsInRoot()) }
                            .graphicsLayer {
                                val s = if (survole == c.id) 1.08f else 1f
                                scaleX = s
                                scaleY = s
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(c.colore))
                            .then(
                                if (survole == c.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (compte) {
                                0 -> testo(R.string.empty)
                                1 -> testo(R.string.n_1_note)
                                else -> testo(R.string.compte_notes)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (Color(c.colore).luminance() > 0.62f) Color(0xFF271E52) else Color.White,
                        )
                    }
                    Text(
                        c.nome,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfaceBarre(modifier: Modifier, contenu: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = contenu)
    }
}

private fun Color.assombrie(facteur: Float = 0.72f) = Color(red * facteur, green * facteur, blue * facteur, alpha)

private fun Color.luminance() = red * 0.299f + green * 0.587f + blue * 0.114f
