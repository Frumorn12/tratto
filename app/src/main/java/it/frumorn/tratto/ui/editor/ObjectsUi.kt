package it.frumorn.tratto.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Allineamento
import it.frumorn.tratto.data.COLORE_TESTO
import it.frumorn.tratto.data.Carattere
import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.StileParagrafo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.theme.Manrope
import kotlin.math.roundToInt

/**
 * Il selettore di foto di sistema (senza permessi). La funzione restituita lo apre: l'immagine scelta
 * va nel punto dato (sullo schermo) o, con null, al centro della pagina visibile.
 */
@Composable
fun rememberSceltaImmagine(s: EditorSession): (Offset?) -> Unit {
    var punto by remember { mutableStateOf<Offset?>(null) }
    val scelta = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) s.oggetti.inserisciImmagine(uri, punto)
    }
    return { p ->
        punto = p
        scelta.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}

/** Il menu della pressione lunga del dito su un punto vuoto della pagina. */
@Composable
fun MenuContestuale(s: EditorSession, scegliImmagine: (Offset?) -> Unit) {
    val punto = s.menuContestuale ?: return
    val incollabile = remember(punto) { s.oggetti.appuntiDisponibili() }
    Box(Modifier.offset { IntOffset(punto.x.roundToInt(), punto.y.roundToInt()) }.size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { s.menuContestuale = null }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(
                text = { Text(testo(R.string.paste)) }, enabled = incollabile,
                leadingIcon = { Icona(R.drawable.ic_content_paste, null) },
                onClick = { s.menuContestuale = null; s.oggetti.incolla(punto) },
            )
            DropdownMenuItem(
                text = { Text(testo(R.string.insert_an_image_2)) },
                leadingIcon = { Icona(R.drawable.ic_add_photo_alternate, null) },
                onClick = { s.menuContestuale = null; scegliImmagine(punto) },
            )
            DropdownMenuItem(
                text = { Text(testo(R.string.text_here)) },
                leadingIcon = { Icona(R.drawable.ic_title, null) },
                onClick = { s.menuContestuale = null; s.oggetti.testoQui(punto) },
            )
        }
    }
}

/** La barra dell'oggetto selezionato: stesso aspetto della barra del lazo. */
@Composable
fun BarraOggetto(s: EditorSession, modifier: Modifier) {
    // Tiene l'ultimo oggetto mentre la barra si chiude, cosi' l'animazione d'uscita non cambia pulsanti.
    var ultimo by remember { mutableStateOf(s.oggetto) }
    s.oggetto?.let { ultimo = it }
    @Suppress("UNUSED_VARIABLE") val versione = s.versioneOggetto
    val (indice, quanti) = s.vista.ordineOggetto()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ultimo is Testo) AzioneSelezione(R.drawable.ic_edit, testo(R.string.edit)) { s.oggetti.modifica() }
            AzioneSelezione(R.drawable.ic_content_copy, testo(R.string.copy)) { s.oggetti.copia() }
            AzioneSelezione(R.drawable.ic_library_add, testo(R.string.duplicate)) { s.oggetti.duplica() }
            AzioneSelezione(R.drawable.ic_flip_to_front, testo(R.string.forward), attivo = indice in 0 until quanti - 1) { s.oggetti.avanti() }
            AzioneSelezione(R.drawable.ic_flip_to_back, testo(R.string.backward), attivo = indice > 0) { s.oggetti.indietro() }
            AzioneSelezione(R.drawable.ic_delete, testo(R.string.delete)) { s.oggetti.elimina() }
            AzioneSelezione(R.drawable.ic_close, testo(R.string.close)) { s.oggetti.chiudi() }
        }
    }
}

// ---------------------------------------------------------------- barra del testo

/** Colori per evidenziare: quelli della tavolozza, trasparenti come l'evidenziatore. */
private val EVIDENZIATORI = listOf(0xFFF2B705, 0xFFFF7A1A, 0xFFE0354B, 0xFFC0399E, 0xFF6546F3, 0xFF2F6BFF, 0xFF0FA3B1, 0xFF1E9E5A)
    .map { ((it and 0xFFFFFF) or 0x66000000).toInt() }

private val DIMENSIONI = listOf(8f, 9f, 10f, 11f, 12f, 14f, 18f, 24f, 30f, 36f, 48f, 60f, 72f, 96f)

/** Non ruba il fuoco al campo: la tastiera resta aperta mentre si scelgono stile, carattere e colori. */
private val SENZA_FUOCO = PopupProperties(focusable = false)

/**
 * La barra di formato della casella di testo, come quella di Google Documenti: stile del paragrafo,
 * carattere, dimensione, grassetto, corsivo, sottolineato, barrato, colore, evidenziazione,
 * allineamento, elenchi, cancella formattazione. Sta sopra la tastiera; i pulsanti mostrano il
 * formato del testo selezionato (o sotto il cursore).
 */
@Composable
fun BarraTesto(s: EditorSession, modifier: Modifier) {
    val campo = s.campoTesto ?: return
    val f = s.formato
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    MenuStile(f.stile) { campo.stile(it) }
                    Divisore()
                    MenuCarattere(f.carattere) { campo.carattere(it) }
                    Divisore()
                    BottoneIcona(R.drawable.ic_remove, testo(R.string.decrease_size), dimensione = 40.dp) { campo.dimensione(-1f) }
                    MenuDimensione(f.punti) { campo.impostaDimensione(it) }
                    BottoneIcona(R.drawable.ic_add, testo(R.string.increase_size), dimensione = 40.dp) { campo.dimensione(1f) }
                    Divisore()
                    BottoneIcona(R.drawable.ic_format_bold, testo(R.string.bold), selezionato = f.grassetto, dimensione = 40.dp) { campo.grassetto() }
                    BottoneIcona(R.drawable.ic_format_italic, testo(R.string.italic), selezionato = f.corsivo, dimensione = 40.dp) { campo.corsivo() }
                    BottoneIcona(R.drawable.ic_format_underlined, testo(R.string.underline), selezionato = f.sottolineato, dimensione = 40.dp) { campo.sottolineato() }
                    BottoneIcona(R.drawable.ic_format_strikethrough, testo(R.string.strikethrough), selezionato = f.barrato, dimensione = 40.dp) { campo.barrato() }
                    MenuColore(R.drawable.ic_format_color_text, testo(R.string.text_color), f.colore, StatoStrumenti.TAVOLOZZA, testo(R.string.automatic)) { campo.colore(it) }
                    MenuColore(R.drawable.ic_format_ink_highlighter, testo(R.string.highlight_color), f.evidenziato, EVIDENZIATORI, testo(R.string.none)) { campo.evidenzia(it) }
                    Divisore()
                    MenuAllineamento(f.allineamento) { campo.allineamento(it) }
                    BottoneIcona(R.drawable.ic_format_list_bulleted, testo(R.string.bulleted_list), selezionato = f.elenco == Elenco.PUNTATO, dimensione = 40.dp) { campo.elenco(Elenco.PUNTATO) }
                    BottoneIcona(R.drawable.ic_format_list_numbered, testo(R.string.numbered_list), selezionato = f.elenco == Elenco.NUMERATO, dimensione = 40.dp) { campo.elenco(Elenco.NUMERATO) }
                    Divisore()
                    BottoneIcona(R.drawable.ic_format_clear, testo(R.string.clear_formatting), dimensione = 40.dp) { campo.pulisci() }
                }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                BottoneIcona(R.drawable.ic_check, testo(R.string.done), selezionato = true, dimensione = 44.dp) { s.vista.chiudiTesto() }
            }
        }
    }
}

@Composable
private fun Divisore() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Pulsante con testo e freccia che apre un menu, come i menu a tendina di Documenti. */
@Composable
private fun Tendina(etichetta: String, descrizione: String, larghezza: Int, stile: TextStyle = MaterialTheme.typography.labelLarge, menu: @Composable (chiudi: () -> Unit) -> Unit) {
    var aperto by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = descrizione) { aperto = true }
                .semantics { contentDescription = "$descrizione: $etichetta" }
                .padding(horizontal = 8.dp, vertical = 10.dp).widthIn(min = larghezza.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Il testo non si restringe: nella barra stretta la dimensione "14" diventava "1".
            Text(etichetta, style = stile, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(2.dp))
            Icona(R.drawable.ic_keyboard_arrow_down, null, dimensione = 18.dp)
        }
        DropdownMenu(aperto, { aperto = false }, properties = SENZA_FUOCO, shape = MaterialTheme.shapes.medium) {
            menu { aperto = false }
        }
    }
}

@Composable
private fun MenuStile(attuale: StileParagrafo?, onScelta: (StileParagrafo) -> Unit) {
    Tendina(attuale?.etichetta() ?: testo(R.string.style), testo(R.string.paragraph_style), 112) { chiudi ->
        StileParagrafo.entries.forEach { st ->
            DropdownMenuItem(
                text = {
                    // Ogni voce si vede con il suo stile, come in Documenti (un po' rimpicciolita).
                    val colore = if (st.colore == COLORE_TESTO) MaterialTheme.colorScheme.onSurface else Color(st.colore)
                    Text(st.etichetta(), fontFamily = Manrope, fontSize = minOf(st.punti * 1.05f, 26f).sp, color = colore)
                },
                trailingIcon = { if (st == attuale) Icona(R.drawable.ic_check, null, dimensione = 18.dp) },
                onClick = { chiudi(); onScelta(st) },
            )
        }
    }
}

private fun famiglia(c: Carattere) = when (c) {
    Carattere.MANROPE -> Manrope
    Carattere.SERIF -> FontFamily.Serif
    Carattere.MONO -> FontFamily.Monospace
    Carattere.ARIAL -> FontFamily.SansSerif
}

@Composable
private fun MenuCarattere(attuale: Carattere?, onScelta: (Carattere) -> Unit) {
    Tendina(
        attuale?.nome ?: testo(R.string.font), testo(R.string.font), 92,
        stile = MaterialTheme.typography.labelLarge.copy(fontFamily = attuale?.let { famiglia(it) } ?: Manrope),
    ) { chiudi ->
        Carattere.entries.forEach { c ->
            DropdownMenuItem(
                text = { Text(c.nome, fontFamily = famiglia(c), fontSize = 16.sp) },
                trailingIcon = { if (c == attuale) Icona(R.drawable.ic_check, null, dimensione = 18.dp) },
                onClick = { chiudi(); onScelta(c) },
            )
        }
    }
}

private fun punti(p: Float) = if (p == p.roundToInt().toFloat()) p.roundToInt().toString() else p.toString().replace('.', ',')

@Composable
private fun MenuDimensione(attuale: Float?, onScelta: (Float) -> Unit) {
    Tendina(attuale?.let { punti(it) } ?: "–", testo(R.string.text_size), 44, stile = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) { chiudi ->
        DIMENSIONI.forEach { d ->
            DropdownMenuItem(
                text = { Text(punti(d)) },
                trailingIcon = { if (d == attuale) Icona(R.drawable.ic_check, null, dimensione = 18.dp) },
                onClick = { chiudi(); onScelta(d) },
            )
        }
    }
}

/** Icona con la barretta del colore attuale sotto (come la "A" di Documenti) e il menu dei colori. */
@Composable
private fun MenuColore(icona: Int, descrizione: String, attuale: Int?, colori: List<Int>, nessuno: String, onScelta: (Int?) -> Unit) {
    var aperto by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(interactionSource = null, indication = ripple(), onClickLabel = descrizione) { aperto = true }
                .semantics { contentDescription = descrizione },
            contentAlignment = Alignment.Center,
        ) {
            Icona(icona, null)
            // La barretta dell'icona e' in basso (da 800 a 960 su 960): la si copre con il colore scelto.
            if (attuale != null) {
                Box(Modifier.align(Alignment.Center).offset(y = 10.dp).width(20.dp).height(4.dp).background(Color(attuale)))
            }
        }
        DropdownMenu(aperto, { aperto = false }, properties = SENZA_FUOCO, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(descrizione.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                GrigliaColori(colori, attuale ?: 0) { aperto = false; onScelta(it) }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { aperto = false; onScelta(null) }) { Text(nessuno) }
            }
        }
    }
}

private fun iconaAllineamento(a: Allineamento?) = when (a) {
    Allineamento.CENTRO -> R.drawable.ic_format_align_center
    Allineamento.DESTRA -> R.drawable.ic_format_align_right
    Allineamento.GIUSTIFICATO -> R.drawable.ic_format_align_justify
    else -> R.drawable.ic_format_align_left
}

@Composable
private fun nomeAllineamento(a: Allineamento) = when (a) {
    Allineamento.SINISTRA -> testo(R.string.align_left)
    Allineamento.CENTRO -> testo(R.string.center)
    Allineamento.DESTRA -> testo(R.string.align_right)
    Allineamento.GIUSTIFICATO -> testo(R.string.justify)
}

@Composable
private fun StileParagrafo.etichetta() = when (this) {
    StileParagrafo.NORMALE -> testo(R.string.normal_text)
    StileParagrafo.TITOLO -> testo(R.string.title)
    StileParagrafo.SOTTOTITOLO -> testo(R.string.subtitle)
    StileParagrafo.INTESTAZIONE1 -> testo(R.string.heading_1)
    StileParagrafo.INTESTAZIONE2 -> testo(R.string.heading_2)
    StileParagrafo.INTESTAZIONE3 -> testo(R.string.heading_3)
}

@Composable
private fun MenuAllineamento(attuale: Allineamento?, onScelta: (Allineamento) -> Unit) {
    var aperto by remember { mutableStateOf(false) }
    val alignement = testo(R.string.alignment)
    Box {
        Row(
            Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = alignement) { aperto = true }
                .semantics { contentDescription = alignement }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icona(iconaAllineamento(attuale), null)
            Icona(R.drawable.ic_keyboard_arrow_down, null, dimensione = 18.dp)
        }
        DropdownMenu(aperto, { aperto = false }, properties = SENZA_FUOCO, shape = MaterialTheme.shapes.medium) {
            Allineamento.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(nomeAllineamento(a)) },
                    leadingIcon = { Icona(iconaAllineamento(a), null) },
                    trailingIcon = { if (a == attuale) Icona(R.drawable.ic_check, null, dimensione = 18.dp) },
                    onClick = { aperto = false; onScelta(a) },
                )
            }
        }
    }
}
