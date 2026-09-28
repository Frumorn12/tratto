package it.frumorn.tratto.googledocs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Log
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Allineamento
import it.frumorn.tratto.data.Carattere
import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.UNITA_PER_PUNTO
import it.frumorn.tratto.editor.Caratteri
import it.frumorn.tratto.editor.LivelloDocumento
import it.frumorn.tratto.editor.ParagrafoDocumento
import kotlin.math.roundToInt

/**
 * I caratteri del documento: Arial (il predefinito di Documenti) e i suoi simili diventano Liberation
 * Sans, che ha le stesse misure, cosi' le righe vanno a capo nello stesso punto e i disegni cadono
 * sulle stesse parole; i caratteri con grazie e a spaziatura fissa quelli di sistema.
 */
internal class CaratteriDoc(context: Context) {
    private val liberation: Typeface = try {
        val r = context.resources
        val famiglia = FontFamily.Builder(Font.Builder(r, R.font.liberation_sans_regular).setWeight(400).build())
            .addFont(Font.Builder(r, R.font.liberation_sans_bold).setWeight(700).build())
            .addFont(Font.Builder(r, R.font.liberation_sans_italic).setWeight(400).setSlant(1).build())
            .addFont(Font.Builder(r, R.font.liberation_sans_bold_italic).setWeight(700).setSlant(1).build())
            .build()
        Typeface.CustomFallbackBuilder(famiglia).setSystemFallback("sans-serif").build()
    } catch (e: Exception) {
        Log.w("CaratteriDoc", "Liberation Sans non disponibile", e)
        Typeface.SANS_SERIF
    }

    private val cache = HashMap<String, Typeface>()

    fun di(f: FormatoDoc): Typeface = synchronized(cache) {
        cache.getOrPut("${f.famiglia}|${f.grassetto}|${f.corsivo}") {
            val nome = f.famiglia.lowercase()
            val base = when {
                nome == "manrope" -> Caratteri.di(Carattere.MANROPE, grassetto = false, corsivo = false)
                nome.contains("mono") || nome.contains("courier") || nome.contains("consol") || nome.contains("code") -> Typeface.MONOSPACE
                nome.contains("serif") && !nome.contains("sans") -> Typeface.SERIF
                nome in SERIF -> Typeface.SERIF
                nome == "roboto" -> Typeface.SANS_SERIF
                else -> liberation
            }
            Typeface.create(base, if (f.grassetto) 700 else 400, f.corsivo)
        }
    }

    private companion object {
        val SERIF = setOf("times new roman", "georgia", "merriweather", "lora", "playfair display", "garamond", "eb garamond", "cambria", "spectral", "libre baskerville", "crimson text", "tinos")
    }
}

/**
 * Il documento impaginato come in Google Docs, sulle pagine di Tratto (larghe 1000 unita'): ogni
 * paragrafo e' uno StaticLayout, spezzato fra le pagine a righe intere. Le misure del documento sono
 * in punti; i layout lavorano in unita' x [K], cosi' anche le dimensioni dei caratteri (che uno span
 * vuole intere) restano precise.
 *
 * [immagine] da' la bitmap di un'immagine del documento (in linea o posizionata) se e' gia' scaricata.
 * Gli oggetti posizionati in [escludi] (i disegni di Tratto) non si disegnano: l'inchiostro c'e' gia'.
 */
internal class PagineDoc(
    private val m: ModelloDoc,
    private val caratteri: CaratteriDoc,
    /** Letto quando si disegna: i disegni appena messi da Tratto spariscono senza impaginare di nuovo. */
    private val escludi: () -> Set<String>,
    private val immagine: (String) -> Bitmap?,
) : LivelloDocumento {

    /** Un pezzo del paragrafo [paragrafo] su una pagina: le righe da [prima] a [ultima] del layout, con la prima in ([x], [y]). */
    private class Fetta(val layout: StaticLayout, val prima: Int, val ultima: Int, val x: Float, val y: Float, val paragrafo: Int) {
        val altezza: Float get() = (layout.getLineBottom(ultima) - layout.getLineTop(prima)) / K
    }

    /** Simbolo di un elenco: il testo e dove va (base della prima riga). */
    private class Simbolo(val testo: String, val paint: TextPaint, val x: Float, val y: Float, val paragrafo: Int)

    /** Un paragrafo del modello [m] (indice [indice], -1 per uno nuovo in fondo), per [DocumentoVivo]. */
    class Rif(val modello: ModelloDoc, val indice: Int)

    /** Paragrafi da non disegnare: quelli che si stanno modificando (li mostra il campo di testo). */
    @Volatile var nascosti: Set<Int> = emptySet()

    /** Dove finisce il testo: pagina e altezza sotto l'ultimo paragrafo. */
    private var fineTesto = 0 to 0f
    private var sinistraTesto = 0f
    private var larghezzaTesto = 0f

    /** Dove e' finito un paragrafo: pagina e cima della sua prima riga, in unita'. */
    class Posizione(val pagina: Int, val y: Float, val x: Float, val altezza: Float)

    /** Unita' di pagina per punto. */
    val u: Float = (1000.0 / m.larghezza).toFloat()
    override val altezza: Float = (m.altezza * u).toFloat()

    private val fette = ArrayList<ArrayList<Fetta>>()
    private val simboli = ArrayList<ArrayList<Simbolo>>()
    private val davanti = ArrayList<ArrayList<Pair<String, RectF>>>()
    private val dietro = ArrayList<ArrayList<Pair<String, RectF>>>()

    /** Posizione di ogni paragrafo del modello, nello stesso ordine. */
    val posizioni = ArrayList<Posizione>()

    override val pagine: Int get() = fette.size

    init {
        impagina()
    }

    private fun pagina(k: Int) {
        while (fette.size <= k) {
            fette.add(ArrayList()); simboli.add(ArrayList()); davanti.add(ArrayList()); dietro.add(ArrayList())
        }
    }

    private fun impagina() {
        val sinistra = (m.margineSinistro * u).toFloat()
        val larghezza = ((m.larghezza - m.margineSinistro - m.margineDestro) * u).toFloat().coerceAtLeast(50f)
        val cima = (m.margineSu * u).toFloat()
        val fondo = (altezza - m.margineGiu * u).toFloat().coerceAtLeast(cima + 50f)
        var pagina = 0
        var y = cima
        pagina(0)
        sinistraTesto = sinistra
        larghezzaTesto = larghezza
        for ((k, p) in m.paragrafi.withIndex()) {
            y += (p.sopra * u).toFloat()
            val layout = layout(p, larghezza)
            val righe = layout.lineCount
            var inizio = 0
            var primaPagina = -1
            var primaY = 0f
            while (inizio < righe) {
                // Quante righe stanno in questa pagina (almeno una, se la pagina e' vuota).
                var fine = inizio
                val base = layout.getLineTop(inizio) / K
                while (fine < righe && y + (layout.getLineBottom(fine) / K - base) <= fondo) fine++
                if (fine == inizio) {
                    if (y > cima + 0.5f) {
                        pagina++; pagina(pagina); y = cima
                        continue
                    }
                    fine = inizio + 1
                }
                fette[pagina] += Fetta(layout, inizio, fine - 1, sinistra, y, k)
                if (primaPagina < 0) {
                    primaPagina = pagina
                    primaY = y
                }
                y += layout.getLineBottom(fine - 1) / K - base
                inizio = fine
                if (inizio < righe) {
                    pagina++; pagina(pagina); y = cima
                }
            }
            if (primaPagina < 0) {
                primaPagina = pagina
                primaY = y
            }
            posizioni += Posizione(primaPagina, primaY, sinistra, y - primaY)

            // Il simbolo dell'elenco sulla prima riga, nel rientro.
            p.elenco?.let { s ->
                val tp = paint(p.formatoElenco ?: p.formatoBase)
                val b = primaY + layout.getLineBaseline(0) / K - layout.getLineTop(0) / K
                simboli[primaPagina] += Simbolo(s, tp, sinistra + (p.rientroPrimaRiga * u).toFloat(), b, k)
            }
            // Oggetti posizionati: rispetto alla cima del paragrafo e al margine sinistro.
            for (id in p.posizionati) {
                val o = m.posizionati[id] ?: continue
                val x = sinistra + (o.sinistra * u).toFloat()
                val yy = primaY + (o.alto * u).toFloat()
                val r = RectF(x, yy, x + (o.larghezza * u).toFloat(), yy + (o.altezza * u).toFloat())
                (if (o.disposizione == "BEHIND_TEXT") dietro else davanti)[primaPagina] += id to r
            }

            y += (p.sotto * u).toFloat()
            if (p.interruzione) {
                pagina++; pagina(pagina); y = cima
            }
        }
        fineTesto = pagina to y
    }

    private fun paint(f: FormatoDoc): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = caratteri.di(f)
        textSize = (f.punti * u).toFloat()
        color = f.colore ?: Color.BLACK
    }

    /** Il layout di un paragrafo, in unita' x [K]. */
    private fun layout(p: ParagrafoDoc, larghezza: Float): StaticLayout {
        val sb = SpannableStringBuilder()
        for (x in p.pezzi) {
            val a = sb.length
            sb.append(x.testo)
            val b = sb.length
            if (b == a) continue
            val f = x.formato
            val img = x.immagine
            if (img != null) {
                val d = m.immagini[img]
                if (d != null) sb.setSpan(SpanImmagine(img, (d.larghezza * u * K).toFloat(), (d.altezza * u * K).toFloat()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            sb.setSpan(AbsoluteSizeSpan((f.punti * u * K).roundToInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(TypefaceSpan(caratteri.di(f)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(f.colore ?: Color.BLACK), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            f.sfondo?.let { sb.setSpan(BackgroundColorSpan(it), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (f.sottolineato) sb.setSpan(UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.barrato) sb.setSpan(StrikethroughSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when (f.spostamento) {
                "SUPERSCRIPT" -> {
                    sb.setSpan(SuperscriptSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(AbsoluteSizeSpan((f.punti * u * K * 0.66).roundToInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "SUBSCRIPT" -> {
                    sb.setSpan(SubscriptSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(AbsoluteSizeSpan((f.punti * u * K * 0.66).roundToInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        val rientro = ((if (p.elenco != null) p.rientroInizio else p.rientroPrimaRiga) * u * K).toInt()
        val resto = (p.rientroInizio * u * K).toInt()
        if (sb.isNotEmpty() && (rientro != 0 || resto != 0)) sb.setSpan(LeadingMarginSpan.Standard(rientro, resto), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        val base = paint(p.formatoBase).apply { textSize *= K }
        val w = (larghezza * K).toInt().coerceAtLeast(1)
        return StaticLayout.Builder.obtain(sb, 0, sb.length, base, w)
            .setAlignment(
                when (p.allineamento) {
                    "CENTER" -> Layout.Alignment.ALIGN_CENTER
                    "END" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            // Documenti misura l'interlinea "singola" come salita + discesa + spazio del carattere
            // (1,15 volte la dimensione per Arial); il layout usa solo salita + discesa.
            .setLineSpacing(0f, (p.interlinea / 100.0 * INTERLINEA_ARIAL).toFloat())
            .setIncludePad(false)
            .setUseLineSpacingFromFallbacks(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(if (p.allineamento == "JUSTIFIED") LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()
    }

    /** Un'immagine in linea grande [w] x [h] (unita' x K): occupa il suo posto nella riga. */
    private inner class SpanImmagine(val id: String, val w: Float, val h: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                fm.ascent = -h.roundToInt()
                fm.top = fm.ascent
                fm.descent = 0
                fm.bottom = 0
            }
            return w.roundToInt()
        }

        override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val r = RectF(x, y - h, x + w, y.toFloat())
            val bmp = immagine(id)
            if (bmp != null) c.drawBitmap(bmp, null, r, pImmagine) else c.drawRect(r, pSegnaposto)
        }
    }

    override fun disegna(c: Canvas, indice: Int, pxPerUnita: Float) {
        if (indice !in fette.indices) return
        val esclusi = escludi()
        for ((id, r) in dietro[indice]) if (id !in esclusi) disegnaPosizionato(c, id, r)
        val nascosti = nascosti
        for (f in fette[indice]) {
            if (f.paragrafo in nascosti) continue
            val s = c.save()
            c.translate(f.x, f.y)
            c.scale(1f / K, 1f / K)
            val top = f.layout.getLineTop(f.prima).toFloat()
            c.translate(0f, -top)
            c.clipRect(0f, top, f.layout.width.toFloat(), f.layout.getLineBottom(f.ultima).toFloat())
            f.layout.draw(c)
            c.restoreToCount(s)
        }
        for (s in simboli[indice]) if (s.paragrafo !in nascosti) c.drawText(s.testo, s.x, s.y, s.paint)
        for ((id, r) in davanti[indice]) if (id !in esclusi) disegnaPosizionato(c, id, r)
    }

    override fun paragrafo(indice: Int, x: Float, y: Float): ParagrafoDocumento? {
        val (paginaFine, yFine) = fineTesto
        // Sotto la fine del testo: un paragrafo nuovo in fondo.
        if (indice > paginaFine || (indice == paginaFine && y > yFine)) {
            val vuoto = TestoRicco(listOf(Paragrafo(listOf(Frammento("", punti = puntiTratto(m.paragrafi.lastOrNull()?.formatoBase ?: FormatoDoc()))))))
            return ParagrafoDocumento(paginaFine, sinistraTesto, yFine, larghezzaTesto, vuoto, Rif(m, -1))
        }
        val sulla = fette.getOrNull(indice) ?: return null
        val f = sulla.firstOrNull { y >= it.y - 3f && y <= it.y + it.altezza + 3f }
            ?: sulla.minByOrNull { minOf(kotlin.math.abs(y - it.y), kotlin.math.abs(y - it.y - it.altezza)) }
            ?: return null
        val k = f.paragrafo
        val p = m.paragrafi[k]
        val pos = posizioni[k]
        val rientro = (p.rientroInizio * u).toFloat()
        return ParagrafoDocumento(pos.pagina, pos.x + rientro, pos.y, larghezzaTesto - rientro, testoRicco(p), Rif(m, k))
    }

    /** Il paragrafo come testo di Tratto, per il campo che lo modifica (carattere di Tratto, misure del documento). */
    private fun testoRicco(p: ParagrafoDoc): TestoRicco {
        val fr = p.pezzi.map { x ->
            val f = x.formato
            Frammento(x.testo, f.grassetto, f.corsivo, f.sottolineato, f.barrato, f.colore, f.sfondo, puntiTratto(f), carattereTratto(f.famiglia))
        }.ifEmpty { listOf(Frammento("", punti = puntiTratto(p.formatoBase))) }
        val al = when (p.allineamento) {
            "CENTER" -> Allineamento.CENTRO
            "END" -> Allineamento.DESTRA
            "JUSTIFIED" -> Allineamento.GIUSTIFICATO
            else -> Allineamento.SINISTRA
        }
        return TestoRicco(listOf(Paragrafo(fr, allineamento = al)))
    }

    /** I punti di Tratto sono di un A4 largo 1000 unita': si scala se la pagina del documento e' diversa. */
    private fun puntiTratto(f: FormatoDoc): Float = (f.punti * u / UNITA_PER_PUNTO).toFloat()

    private fun carattereTratto(famiglia: String): Carattere {
        val n = famiglia.lowercase()
        return when {
            n.contains("mono") || n.contains("courier") -> Carattere.MONO
            n.contains("serif") && !n.contains("sans") || n.contains("times") || n.contains("georgia") -> Carattere.SERIF
            else -> Carattere.MANROPE
        }
    }

    private fun disegnaPosizionato(c: Canvas, id: String, r: RectF) {
        val bmp = immagine(id)
        if (bmp != null) c.drawBitmap(bmp, null, r, pImmagine) else c.drawRect(r, pSegnaposto)
    }

    companion object {
        /** Precisione dei layout: unita' di pagina x K. */
        const val K = 8f

        /** Interlinea singola di Documenti rispetto a quella del layout, per Arial / Liberation Sans. */
        const val INTERLINEA_ARIAL = 1.15 / 1.117

        private val pImmagine = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val pSegnaposto = Paint().apply { color = 0x14000000 }
    }
}
