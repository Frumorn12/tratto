package it.frumorn.tratto.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import it.frumorn.tratto.data.Allineamento
import it.frumorn.tratto.data.COLORE_TESTO
import it.frumorn.tratto.data.Carattere
import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.StileParagrafo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.UNITA_PER_PUNTO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dal testo ricco al testo di Android e ritorno. Lo stesso Spannable serve alla pagina (StaticLayout)
 * e al campo che lo modifica (EditText), con gli stessi parametri di impaginazione: le righe vanno a
 * capo negli stessi punti mentre si scrive e dopo.
 *
 * Tutte le misure sono in unita' di pagina (un "pixel" del layout e' un'unita'): chi disegna scala il
 * canvas, chi modifica scala la vista. Cosi' lo zoom non cambia dove vanno a capo le righe.
 *
 * Ogni paragrafo ha uno [SpanParagrafo] e ogni frammento uno [SpanFrammento]. Il paragrafo si applica
 * per primo (priorita' piu' alta), cosi' il formato del frammento vince su quello dello stile.
 */
object Impaginazione {
    /** Interlinea, come quella predefinita di Google Documenti. */
    const val INTERLINEA = 1.15f

    /** Rientro di un livello, in unita' di pagina (mezzo pollice, come in Documenti). */
    const val RIENTRO = 36f * UNITA_PER_PUNTO

    private const val PRIORITA_PARAGRAFO = 16

    /** Il pennello di base, uguale a quello del campo di testo. */
    fun pennello(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = StileParagrafo.NORMALE.punti * UNITA_PER_PUNTO
        color = COLORE_TESTO
        typeface = Caratteri.di(Carattere.MANROPE, grassetto = false, corsivo = false)
    }

    fun spannable(t: TestoRicco): SpannableStringBuilder = SpannableStringBuilder(t.testoSemplice).also { applica(it, t) }

    /**
     * Mette in [s] (che ha gia' il testo di [t]) gli span di [t], togliendo quelli di prima: anche quelli
     * di formato arrivati da fuori (un incolla da un'altra app), che a questo punto sono gia' dentro [t].
     * Lascia stare gli span della tastiera (testo in composizione, suggerimenti).
     */
    fun applica(s: Spannable, t: TestoRicco) {
        for (sp in s.getSpans(0, s.length, Any::class.java)) {
            if (sp is SpanParagrafo || (formato(sp) && !composizione(s, sp))) s.removeSpan(sp)
        }
        for ((range, span) in spanParagrafi(t, s.length)) {
            s.setSpan(span, range.first, range.last, Spanned.SPAN_INCLUSIVE_INCLUSIVE or (PRIORITA_PARAGRAFO shl Spanned.SPAN_PRIORITY_SHIFT))
        }
        var o = 0
        for (p in t.paragrafi) {
            for (f in p.frammenti) {
                val fine = (o + f.testo.length).coerceAtMost(s.length)
                if (fine > o && f != Frammento(f.testo)) s.setSpan(SpanFrammento(f.soloCarattere()), o, fine, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
                o += f.testo.length
            }
            o++
        }
    }

    /**
     * Gli span dei paragrafi (con inizio e fine inclusi nel "range") per un testo lungo [lunghezza].
     * Lo span copre anche l'"a capo" finale, tranne prima dell'ultimo paragrafo vuoto: quello ha uno
     * span vuoto in fondo, e il paragrafo prima non deve toccarlo, se no Android li applicherebbe
     * tutti e due all'ultima riga.
     */
    fun spanParagrafi(t: TestoRicco, lunghezza: Int): List<Pair<IntRange, SpanParagrafo>> {
        val out = ArrayList<Pair<IntRange, SpanParagrafo>>()
        val contatori = IntArray(10)
        var o = 0
        for ((k, p) in t.paragrafi.withIndex()) {
            val fine = o + p.lunghezza
            val ultimo = k == t.paragrafi.lastIndex
            val primaDellUltimoVuoto = k == t.paragrafi.lastIndex - 1 && t.paragrafi.last().lunghezza == 0
            val fineSpan = if (ultimo || primaDellUltimoVuoto) fine else fine + 1
            val livello = p.rientro.coerceIn(0, contatori.lastIndex)
            val numero = if (p.elenco == Elenco.NUMERATO) {
                for (l in livello + 1 until contatori.size) contatori[l] = 0
                ++contatori[livello]
            } else {
                if (p.elenco == Elenco.NESSUNO) contatori.fill(0)
                0
            }
            out += (o.coerceAtMost(lunghezza)..fineSpan.coerceAtMost(lunghezza)) to SpanParagrafo(p.soloAttributi(), numero)
            o = fine + 1
        }
        return out
    }

    /** Legge il testo ricco da un testo di Android con gli span di Tratto (o di formato, per esempio incollati). */
    fun modello(s: Spanned): TestoRicco {
        val testo = s.toString()
        val paragrafi = ArrayList<Paragrafo>()
        var inizio = 0
        while (true) {
            val a = testo.indexOf('\n', inizio).let { if (it < 0) testo.length else it }
            val attributi = attributiParagrafo(s, inizio, a)
            val frammenti = ArrayList<Frammento>()
            var i = inizio
            while (i < a) {
                val j = s.nextSpanTransition(i, a, CharacterStyle::class.java).coerceIn(i + 1, a)
                frammenti += formatoIn(s, i, j).copy(testo = testo.substring(i, j))
                i = j
            }
            paragrafi += attributi.copy(frammenti = frammenti)
            if (a >= testo.length) break
            inizio = a + 1
        }
        return TestoRicco(paragrafi).normalizzato()
    }

    /**
     * Gli attributi del paragrafo [inizio, fine]: quelli dello span che comincia dentro il paragrafo
     * (il suo); se non c'e', il paragrafo e' nato spezzandone un altro con "a capo" e ne eredita gli
     * attributi, tranne lo stile dei titoli se e' vuoto (come in Documenti: dopo un titolo si torna al
     * testo normale, mentre un elenco continua).
     */
    fun attributiParagrafo(s: Spanned, inizio: Int, fine: Int): Paragrafo {
        val spans = s.getSpans(inizio, max(inizio, fine), SpanParagrafo::class.java)
        val suo = spans.filter { s.getSpanStart(it) in inizio..fine }.minByOrNull { s.getSpanStart(it) }
        if (suo != null) return suo.attributi
        val prima = spans.filter { s.getSpanStart(it) < inizio }.maxByOrNull { s.getSpanStart(it) }
            ?: return Paragrafo()
        val p = prima.attributi
        return if (fine == inizio && p.stile != StileParagrafo.NORMALE) p.copy(stile = StileParagrafo.NORMALE) else p
    }

    /** Il formato dei caratteri in [a, b), che hanno tutti gli stessi span. */
    fun formatoIn(s: Spanned, a: Int, b: Int): Frammento {
        val spans = s.getSpans(a, b, CharacterStyle::class.java).filter {
            s.getSpanStart(it) <= a && s.getSpanEnd(it) >= b && !composizione(s, it)
        }
        // Se due span di Tratto si sovrappongono (testo appena scritto con un formato scelto prima),
        // vale quello che comincia piu' avanti, cioe' il piu' specifico.
        var f = spans.filterIsInstance<SpanFrammento>().maxByOrNull { s.getSpanStart(it) }?.formato ?: Frammento("")
        for (sp in spans) {
            f = when (sp) {
                is StyleSpan -> f.copy(
                    grassetto = f.grassetto || sp.style and Typeface.BOLD != 0,
                    corsivo = f.corsivo || sp.style and Typeface.ITALIC != 0,
                )
                is UnderlineSpan -> f.copy(sottolineato = true)
                is StrikethroughSpan -> f.copy(barrato = true)
                is ForegroundColorSpan -> f.copy(colore = sp.foregroundColor or (0xFF shl 24))
                is BackgroundColorSpan -> if (sp.backgroundColor ushr 24 == 0) f else f.copy(evidenziato = sp.backgroundColor)
                is TypefaceSpan -> f.copy(carattere = when (sp.family) {
                    "serif" -> Carattere.SERIF
                    "monospace" -> Carattere.MONO
                    else -> f.carattere
                })
                else -> f
            }
        }
        return f
    }

    /** Span di formato, nostri o di altri (tolti e rimessi quando si riapplica il testo ricco). */
    private fun formato(sp: Any) = sp is SpanFrammento || sp is StyleSpan || sp is UnderlineSpan || sp is StrikethroughSpan ||
        sp is ForegroundColorSpan || sp is BackgroundColorSpan || sp is TypefaceSpan || sp is AbsoluteSizeSpan || sp is RelativeSizeSpan

    /** Span della tastiera sul testo in composizione: non sono formato. */
    private fun composizione(s: Spanned, sp: Any) = s.getSpanFlags(sp) and Spanned.SPAN_COMPOSING != 0

    // ---------------------------------------------------------------- impaginazione

    /**
     * Impagina una casella. Con paragrafi giustificati e non, il testo si divide in gruppi di paragrafi
     * vicini con lo stesso modo, ognuno con il suo layout (Android giustifica un layout intero, non un
     * paragrafo). I layout usano lo stesso testo con gli indici di tutto il testo, quindi gli spazi fra
     * i paragrafi restano quelli di un layout unico.
     */
    fun impagina(t: Testo): Impaginato {
        val testo = spannable(t.contenuto)
        val w = t.larghezza.roundToInt().coerceAtLeast(1)
        val pennello = pennello()
        val parti = ArrayList<Pair<StaticLayout, Float>>()
        var y = 0f
        var o = 0
        val paragrafi = t.contenuto.paragrafi
        var k = 0
        while (k < paragrafi.size) {
            val giustificato = paragrafi[k].allineamento == Allineamento.GIUSTIFICATO
            val inizio = o
            var fine: Int
            do {
                fine = o + paragrafi[k].lunghezza
                o = fine + 1
                k++
            } while (k < paragrafi.size && (paragrafi[k].allineamento == Allineamento.GIUSTIFICATO) == giustificato)
            val l = layout(testo, inizio, fine, pennello, w, giustificato)
            parti += l to y
            y += l.height
        }
        return Impaginato(parti, y)
    }

    fun layout(testo: CharSequence, inizio: Int, fine: Int, pennello: TextPaint, larghezza: Int, giustificato: Boolean): StaticLayout =
        StaticLayout.Builder.obtain(testo, inizio, fine, pennello, larghezza)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, INTERLINEA)
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(if (giustificato) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()

    /**
     * L'impaginazione della casella per il thread principale, tenuta nell'oggetto stesso (che e'
     * immutabile). Chi disegna in un altro thread chiama [impagina]: un layout non si disegna da due
     * thread insieme.
     */
    fun impaginato(t: Testo): Impaginato = (t.impaginato as? Impaginato) ?: impagina(t).also { t.impaginato = it }

    /** Altezza minima di una casella: una riga di testo normale, anche se e' vuota. */
    val ALTEZZA_MIN = ceil(StileParagrafo.NORMALE.punti * UNITA_PER_PUNTO * 1.4f)
}

/** Una casella impaginata: uno o piu' layout uno sotto l'altro. */
class Impaginato(val parti: List<Pair<StaticLayout, Float>>, altezza: Float) {
    val altezza: Float = max(altezza, Impaginazione.ALTEZZA_MIN)

    /** Disegna con l'angolo in alto a sinistra della casella nell'origine del canvas. */
    fun disegna(c: Canvas) {
        for ((l, y) in parti) {
            val s = c.save()
            c.translate(0f, y)
            l.draw(c)
            c.restoreToCount(s)
        }
    }
}

/** Formato di un frammento: carattere, peso, stile, dimensione, colore, evidenziazione, righe. */
class SpanFrammento(val formato: Frammento) : MetricAffectingSpan() {
    private val carattere = Caratteri.di(formato.carattere, formato.grassetto, formato.corsivo)
    private val dimensione = formato.punti?.let { it * UNITA_PER_PUNTO }

    override fun updateMeasureState(tp: TextPaint) = misura(tp)

    override fun updateDrawState(tp: TextPaint) {
        misura(tp)
        formato.colore?.let { tp.color = it }
        formato.evidenziato?.let { tp.bgColor = it }
        if (formato.sottolineato) tp.isUnderlineText = true
        if (formato.barrato) tp.isStrikeThruText = true
    }

    private fun misura(tp: TextPaint) {
        tp.typeface = carattere
        dimensione?.let { tp.textSize = it }
    }
}

/**
 * Tutto quello che riguarda un paragrafo: dimensione e colore dello stile, allineamento, rientro e
 * pallino o numero dell'elenco, spazio prima e dopo.
 */
class SpanParagrafo(val attributi: Paragrafo, val numero: Int) :
    MetricAffectingSpan(), AlignmentSpan, LeadingMarginSpan, LineHeightSpan {

    private val dimensione = attributi.stile.punti * UNITA_PER_PUNTO
    private val margine = (attributi.rientro * Impaginazione.RIENTRO + if (attributi.elenco != Elenco.NESSUNO) dimensione * 1.6f else 0f).roundToInt()
    private val prima = (attributi.stile.prima * UNITA_PER_PUNTO).roundToInt()
    private val dopo = (attributi.stile.dopo * UNITA_PER_PUNTO).roundToInt()

    /** Stessi attributi e stesso numero: sostituirlo non cambierebbe niente. */
    fun uguale(o: SpanParagrafo) = attributi == o.attributi && numero == o.numero

    override fun updateMeasureState(tp: TextPaint) {
        tp.textSize = dimensione
    }

    override fun updateDrawState(tp: TextPaint) {
        tp.textSize = dimensione
        tp.color = attributi.stile.colore
    }

    override fun getAlignment(): Layout.Alignment = when (attributi.allineamento) {
        Allineamento.CENTRO -> Layout.Alignment.ALIGN_CENTER
        Allineamento.DESTRA -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    override fun getLeadingMargin(first: Boolean): Int = margine

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || attributi.elenco == Elenco.NESSUNO) return
        val sp = text as? Spanned ?: return
        if (sp.getSpanStart(this) != start) return
        val x0 = x + attributi.rientro * Impaginazione.RIENTRO
        val tp = TextPaint(p).apply {
            textSize = dimensione
            color = attributi.stile.colore
            typeface = Caratteri.di(Carattere.MANROPE, grassetto = false, corsivo = false)
        }
        if (attributi.elenco == Elenco.PUNTATO) {
            tp.style = Paint.Style.FILL
            // Pallino pieno al primo livello, vuoto al secondo, quadrato dal terzo (come in Documenti).
            val cx = x0 + dimensione * 0.55f
            val cy = baseline - dimensione * 0.33f
            val r = dimensione * 0.15f
            when (attributi.rientro % 3) {
                0 -> c.drawCircle(cx, cy, r, tp)
                1 -> { tp.style = Paint.Style.STROKE; tp.strokeWidth = r * 0.45f; c.drawCircle(cx, cy, r * 0.85f, tp) }
                else -> c.drawRect(cx - r, cy - r, cx + r, cy + r, tp)
            }
        } else {
            tp.textAlign = Paint.Align.RIGHT
            c.drawText("$numero.", x0 + dimensione * 1.25f, baseline.toFloat(), tp)
        }
    }

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val sp = text as? Spanned ?: return
        val inizio = sp.getSpanStart(this)
        val fine = sp.getSpanEnd(this)
        if (prima > 0 && start == inizio && inizio > 0) {
            fm.ascent -= prima
            fm.top -= prima
        }
        // Ultima riga del paragrafo: finisce con lo span, o subito prima del suo "a capo" (quando il
        // layout e' un pezzo del testo che si ferma li').
        val ultima = end >= fine || (end == fine - 1 && text[end] == '\n')
        if (dopo > 0 && ultima && fine < text.length) {
            fm.descent += dopo
            fm.bottom += dopo
        }
    }
}
