package it.frumorn.tratto.googledocs

import it.frumorn.tratto.data.Allineamento
import it.frumorn.tratto.data.Carattere
import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.StileParagrafo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.UNITA_PER_PUNTO
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Dove sta un blocco nel documento: spazio sopra il paragrafo e rientro da sinistra, in punti interi
 * (cosi' uno spostamento di un pelo non costa un aggiornamento).
 */
internal data class Aspetto(val sopraPt: Int, val rientroPt: Int) {
    val codice: String get() = "$sopraPt,$rientroPt"

    companion object {
        val NESSUNO = Aspetto(0, 0)
    }
}

/** Un pezzo di testo con lo stesso formato, da [inizio] a [fine] nel testo del paragrafo. */
internal data class PezzoDocs(
    val inizio: Int,
    val fine: Int,
    val grassetto: Boolean = false,
    val corsivo: Boolean = false,
    val sottolineato: Boolean = false,
    val barrato: Boolean = false,
    /** ARGB, o null per il colore dello stile. */
    val colore: Int? = null,
    val evidenziato: Int? = null,
    /** Dimensione in punti del documento, o null per quella dello stile. */
    val punti: Double? = null,
    val carattere: String = ContenutoDocs.CARATTERE_TRATTO,
)

/** Un paragrafo di testo come va scritto nel documento. [testo] e' senza a capo e senza caratteri che Google toglierebbe. */
internal data class ParagrafoScritto(
    val testo: String,
    /** namedStyleType di Google: NORMAL_TEXT, TITLE, HEADING_1... */
    val stile: String = "NORMAL_TEXT",
    /** START, CENTER, END, JUSTIFIED. */
    val allineamento: String = "START",
    /** bulletPreset di createParagraphBullets, o null se non e' in un elenco. */
    val elenco: String? = null,
    /** Livello di rientro (in un elenco diventa il livello dell'elenco). */
    val livello: Int = 0,
    val pezzi: List<PezzoDocs> = emptyList(),
) {
    /** Il testo da inserire: in un elenco i tab davanti danno il livello (e Google li toglie). */
    val daInserire: String get() = if (elenco != null) "\t".repeat(livello) + testo else testo
}

/** Un blocco che il documento deve avere, in ordine: il titolo, le figure e i testi delle pagine. */
internal sealed interface BloccoVoluto {
    val chiave: String
    val pagina: String?
    val impronta: String
    val aspetto: Aspetto

    /** Un PNG (trasparente, o la pagina intera di un PDF) di [larghezzaPt] x [altezzaPt] punti. */
    data class Figura(
        override val chiave: String,
        override val pagina: String?,
        override val impronta: String,
        override val aspetto: Aspetto,
        val larghezzaPt: Double,
        val altezzaPt: Double,
    ) : BloccoVoluto

    /** Testo vero. [paragrafi] e' null se la pagina non e' stata letta (il blocco e' quello salvato). */
    data class Scritto(
        override val chiave: String,
        override val pagina: String?,
        override val impronta: String,
        override val aspetto: Aspetto,
        val paragrafi: List<ParagrafoScritto>?,
    ) : BloccoVoluto
}

/** Misure e traduzioni dal foglio di Tratto al documento Google. */
internal object ContenutoDocs {
    /** Punti del documento per unita' di pagina: la pagina larga 1000 occupa 440 punti, dentro i margini di un A4. */
    const val PT_PER_UNITA = 0.44

    /** Pixel dei PNG per unita' di pagina: circa 330 dpi nel documento, nitidi anche ingranditi. */
    const val PX_PER_UNITA = 2f

    /** Spazio massimo sopra un blocco, e quello prima del primo blocco di una pagina che non e' la prima. */
    const val SOPRA_MAX_PT = 48
    const val SOPRA_PAGINA_PT = 24

    /** Rientro di un livello per i paragrafi fuori dagli elenchi. */
    const val RIENTRO_LIVELLO_PT = 18

    /** Altezza massima di una figura: sta in una pagina A4 con i margini normali. */
    const val ALTEZZA_MAX_PT = 690.0

    /** Le dimensioni del testo di Tratto sono pensate per un A4 intero: qui la pagina e' larga 440 punti. */
    val SCALA_TESTO = PT_PER_UNITA * UNITA_PER_PUNTO

    /**
     * Gli aspetti dei blocchi di una pagina in ordine: spazio sopra come la distanza dal blocco prima
     * (il primo di una pagina che non e' la prima ha un po' di spazio), rientro come la distanza dal
     * bordo sinistro.
     */
    fun aspetti(blocchi: List<BloccoPagina>, primaPagina: Boolean): List<Aspetto> {
        var basso: Float? = null
        return blocchi.map { b ->
            val sopra = basso?.let { ((b.riquadro.alto - it) * PT_PER_UNITA).roundToInt().coerceIn(0, SOPRA_MAX_PT) }
                ?: if (primaPagina) 0 else SOPRA_PAGINA_PT
            basso = maxOf(basso ?: Float.NEGATIVE_INFINITY, b.riquadro.basso)
            val rientro = if (b is BloccoPagina.Intera) 0 else (b.riquadro.sinistra.coerceAtLeast(0f) * PT_PER_UNITA).roundToInt()
            Aspetto(sopra, rientro)
        }
    }

    /** Misure in punti di una figura, entro la larghezza della pagina e l'altezza massima. */
    fun misure(b: BloccoPagina): Pair<Double, Double> {
        var w = b.riquadro.larghezza * PT_PER_UNITA
        var h = b.riquadro.altezza * PT_PER_UNITA
        if (h > ALTEZZA_MAX_PT) {
            w *= ALTEZZA_MAX_PT / h
            h = ALTEZZA_MAX_PT
        }
        return Math.round(w.coerceAtLeast(1.0) * 10) / 10.0 to Math.round(h.coerceAtLeast(1.0) * 10) / 10.0
    }

    /** I paragrafi di una casella di testo. */
    fun paragrafi(t: TestoRicco): List<ParagrafoScritto> = t.paragrafi.map { p ->
        val testo = StringBuilder()
        val pezzi = ArrayList<PezzoDocs>()
        for (f in p.frammenti) {
            val s = pulisci(f.testo)
            if (s.isEmpty()) continue
            val inizio = testo.length
            testo.append(s)
            val pezzo = PezzoDocs(
                inizio, testo.length, f.grassetto, f.corsivo, f.sottolineato, f.barrato, f.colore, f.evidenziato,
                f.punti?.let { Math.round(it * SCALA_TESTO * 2) / 2.0 }, carattere(f.carattere),
            )
            // Pezzi vicini con lo stesso formato: una richiesta sola.
            val ultimo = pezzi.lastOrNull()
            if (ultimo != null && ultimo.copy(inizio = 0, fine = 0) == pezzo.copy(inizio = 0, fine = 0)) pezzi[pezzi.lastIndex] = ultimo.copy(fine = pezzo.fine)
            else pezzi += pezzo
        }
        ParagrafoScritto(
            testo.toString(),
            stile(p.stile),
            when (p.allineamento) {
                Allineamento.SINISTRA -> "START"
                Allineamento.CENTRO -> "CENTER"
                Allineamento.DESTRA -> "END"
                Allineamento.GIUSTIFICATO -> "JUSTIFIED"
            },
            when (p.elenco) {
                Elenco.NESSUNO -> null
                Elenco.PUNTATO -> "BULLET_DISC_CIRCLE_SQUARE"
                Elenco.NUMERATO -> "NUMBERED_DECIMAL_ALPHA_ROMAN"
            },
            p.rientro.coerceIn(0, 8),
            pezzi,
        )
    }

    fun stile(s: StileParagrafo) = when (s) {
        StileParagrafo.NORMALE -> "NORMAL_TEXT"
        StileParagrafo.TITOLO -> "TITLE"
        StileParagrafo.SOTTOTITOLO -> "SUBTITLE"
        StileParagrafo.INTESTAZIONE1 -> "HEADING_1"
        StileParagrafo.INTESTAZIONE2 -> "HEADING_2"
        StileParagrafo.INTESTAZIONE3 -> "HEADING_3"
    }

    /** Il carattere di Tratto come famiglia di Google Fonts, che Documenti conosce. */
    fun carattere(c: Carattere) = when (c) {
        Carattere.MANROPE -> CARATTERE_TRATTO
        Carattere.SERIF -> "Noto Serif"
        Carattere.MONO -> "Roboto Mono"
        Carattere.ARIAL -> "Arial"
    }

    /**
     * Testo pronto per il documento: niente a capo (ogni paragrafo e' un elemento a parte) e niente
     * caratteri che Google toglierebbe da solo, cambiando la lunghezza su cui si calcolano gli indici.
     */
    fun pulisci(s: String): String =
        s.replace(Regex("[\\p{Cntrl}  ]"), " ").replace(Regex("[\\p{Co}￼]"), "")

    // ---------------------------------------------------------------- stili nel documento

    /** Campi di paragraphStyle che Tratto decide sui suoi paragrafi. */
    const val CAMPI_PARAGRAFO = "namedStyleType,alignment,indentStart,indentFirstLine,spaceAbove,spaceBelow,lineSpacing"

    /** Campi di textStyle che Tratto decide sul suo testo. */
    const val CAMPI_TESTO = "bold,italic,underline,strikethrough,foregroundColor,backgroundColor,fontSize,weightedFontFamily"

    /** Il paragrafo di una figura: niente spazio sotto e interlinea singola, cosi' le righe restano vicine come sul foglio. */
    fun stileFigura(a: Aspetto): JSONObject = JSONObject()
        .put("namedStyleType", "NORMAL_TEXT")
        .put("alignment", "START")
        .put("indentStart", RichiesteDocs.misura(a.rientroPt.toDouble()))
        .put("indentFirstLine", RichiesteDocs.misura(a.rientroPt.toDouble()))
        .put("spaceAbove", RichiesteDocs.misura(a.sopraPt.toDouble()))
        .put("spaceBelow", RichiesteDocs.misura(0.0))
        .put("lineSpacing", 100)

    /**
     * Un paragrafo di testo: stile con nome, allineamento, rientro (fuori dagli elenchi, che hanno il
     * loro) e, sul primo del blocco, lo spazio sopra. Spazio sotto e interlinea tornano quelli dello stile.
     */
    fun stileScritto(p: ParagrafoScritto, a: Aspetto, primo: Boolean): JSONObject {
        val j = JSONObject().put("namedStyleType", p.stile).put("alignment", p.allineamento)
        if (p.elenco == null) {
            val r = (a.rientroPt + p.livello * RIENTRO_LIVELLO_PT).toDouble()
            j.put("indentStart", RichiesteDocs.misura(r)).put("indentFirstLine", RichiesteDocs.misura(r))
        }
        if (primo && a.sopraPt > 0) j.put("spaceAbove", RichiesteDocs.misura(a.sopraPt.toDouble()))
        return j
    }

    fun stilePezzo(p: PezzoDocs): JSONObject {
        val j = JSONObject()
        if (p.grassetto) j.put("bold", true)
        if (p.corsivo) j.put("italic", true)
        if (p.sottolineato) j.put("underline", true)
        if (p.barrato) j.put("strikethrough", true)
        p.colore?.let { j.put("foregroundColor", RichiesteDocs.colore(it)) }
        p.evidenziato?.let { j.put("backgroundColor", RichiesteDocs.colore(it)) }
        p.punti?.let { j.put("fontSize", RichiesteDocs.misura(it)) }
        j.put("weightedFontFamily", JSONObject().put("fontFamily", p.carattere))
        return j
    }

    /** Un paragrafo lasciato vuoto in fondo al documento da un blocco tolto: torna un paragrafo normale. */
    fun stileNormale(): JSONObject = JSONObject().put("namedStyleType", "NORMAL_TEXT").put("alignment", "START")

    const val CARATTERE_TRATTO = "Manrope"

    /** Nome dell'intervallo che segna un testo di Tratto nel documento. */
    fun nomeIntervallo(chiave: String) = "$PREFISSO_INTERVALLI$chiave"

    const val PREFISSO_INTERVALLI = "tratto/"
}
