package it.frumorn.tratto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.text.LineBreaker
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import it.frumorn.tratto.data.Allineamento
import it.frumorn.tratto.data.Carattere
import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.Formato
import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.StileParagrafo
import it.frumorn.tratto.data.TestoRicco

/**
 * Il campo che modifica una casella di testo, messo sopra la pagina esattamente dove sta la casella.
 *
 * Lavora in unita' di pagina come la pagina stessa (dimensione dei caratteri, larghezza) e viene
 * scalato come vista: cosi' va a capo negli stessi punti del testo disegnato sulla pagina, a qualsiasi
 * zoom. Il testo ricco vive negli span del testo (vedi [Impaginazione]); i comandi di formato leggono
 * il modello, lo cambiano e rimettono gli span, senza toccare il testo (cursore e tastiera non se ne
 * accorgono).
 */
@SuppressLint("AppCompatCustomView", "ViewConstructor")
class CampoTesto(context: Context) : EditText(context) {

    /** Chiamato quando cambia il formato sotto il cursore o nella selezione (per i pulsanti della barra). */
    var alCambioFormato: ((Formato) -> Unit)? = null

    /** Chiamato quando cambia l'altezza del testo (la cornice sulla pagina la segue). */
    var alCambioAltezza: (() -> Unit)? = null

    private var normalizzando = false

    /**
     * Formato scelto con il cursore, senza selezione: vale per quello che si scrive da [pendenteDa] in poi,
     * finche' il cursore resta li' (la tastiera, mentre compone una parola, la riscrive tutta da capo).
     */
    private var pendente: Frammento? = null
    private var pendenteDa = -1
    private var pendenteFino = -1

    /** Un "a capo" appena scritto in questa posizione, o -1. */
    private var aCapo = -1

    /** Testo appena inserito, se piu' lungo di un carattere (incollato?). */
    private var inserito: IntRange? = null

    init {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setLineSpacing(0f, Impaginazione.INTERLINEA)
        isFallbackLineSpacing = true
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        val base = Impaginazione.pennello()
        paintFlags = base.flags
        setTextSize(TypedValue.COMPLEX_UNIT_PX, base.textSize)
        setTextColor(base.color)
        typeface = base.typeface
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // La penna sopra il campo deve restare penna: niente scrittura a mano passata alla tastiera.
        isAutoHandwritingEnabled = false
        // Cursore, selezione e maniglie nel viola dell'app (il tema di sistema li farebbe verde acqua).
        highlightColor = 0x556546F3
        textCursorDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(ACCENTO)
            setSize((base.textSize * 0.08f).toInt().coerceAtLeast(2), base.textSize.toInt())
        }
        listOf(textSelectHandle, textSelectHandleLeft, textSelectHandleRight).forEach { it?.mutate()?.setTint(ACCENTO) }
        textSelectHandle?.let { setTextSelectHandle(it) }
        textSelectHandleLeft?.let { setTextSelectHandleLeft(it) }
        textSelectHandleRight?.let { setTextSelectHandleRight(it) }
        filters = arrayOf(InputFilter { source, start, end, _, dstart, _ -> conPendente(source, start, end, dstart) })
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (normalizzando) return
                aCapo = if (count == 1 && before == 0 && s[start] == '\n') start else -1
                inserito = if (count > 1) start until start + count else null
                if (pendente != null && start == pendenteDa) pendenteFino = start + count
            }

            override fun afterTextChanged(e: Editable) {
                if (normalizzando) return
                normalizzando = true
                try {
                    if (aCapo >= 0) esciDallElencoVuoto(e, aCapo)
                    if (pendente != null) staccaDalPrecedente(e)
                    inserito?.let { r -> if (formatoEstraneo(e, r)) Impaginazione.applica(e, Impaginazione.modello(e)) }
                    normalizzaParagrafi(e)
                } finally {
                    normalizzando = false
                    aCapo = -1
                    inserito = null
                }
                notifica()
            }
        })
    }

    /** Mette nel campo il testo [t] con il cursore in fondo. */
    fun carica(t: TestoRicco) {
        normalizzando = true
        try {
            setText(Impaginazione.spannable(t), BufferType.EDITABLE)
        } finally {
            normalizzando = false
        }
        aggiornaGiustificazione(t)
        setSelection(text.length)
    }

    fun modello(): TestoRicco = Impaginazione.modello(text)

    // ---------------------------------------------------------------- comandi della barra

    fun formato(): Formato {
        val m = modello()
        val a = selectionStart.coerceAtLeast(0)
        val b = selectionEnd.coerceAtLeast(a)
        val f = m.formato(a, b)
        val p = pendente ?: return f
        if (a != b) return f
        val stile = f.stile ?: StileParagrafo.NORMALE
        return f.copy(
            grassetto = p.grassetto, corsivo = p.corsivo, sottolineato = p.sottolineato, barrato = p.barrato,
            colore = p.colore ?: stile.colore, evidenziato = p.evidenziato, punti = p.punti ?: stile.punti, carattere = p.carattere,
        )
    }

    fun grassetto() = alterna({ it.grassetto }) { f, v -> f.copy(grassetto = v) }
    fun corsivo() = alterna({ it.corsivo }) { f, v -> f.copy(corsivo = v) }
    fun sottolineato() = alterna({ it.sottolineato }) { f, v -> f.copy(sottolineato = v) }
    fun barrato() = alterna({ it.barrato }) { f, v -> f.copy(barrato = v) }

    /** Colore del testo; null torna al colore dello stile del paragrafo. */
    fun colore(c: Int?) = caratteri { f, _ -> f.copy(colore = c) }

    fun evidenzia(c: Int?) = caratteri { f, _ -> f.copy(evidenziato = c) }

    fun carattere(c: Carattere) = caratteri { f, _ -> f.copy(carattere = c) }

    /** Ingrandisce o rimpicciolisce di [passo] punti ogni pezzo della selezione, come in Documenti. */
    fun dimensione(passo: Float) = caratteri { f, p -> f.copy(punti = TestoRicco.arrotondaPunti(f.puntiEffettivi(p) + passo)) }

    fun impostaDimensione(punti: Float) = caratteri { f, _ -> f.copy(punti = TestoRicco.arrotondaPunti(punti)) }

    /** Toglie il formato dei caratteri (lo stile dei paragrafi resta). */
    fun pulisci() = caratteri { f, _ -> Frammento(f.testo) }

    /** Stile dei paragrafi: prende dimensione e colore dello stile, come quando si sceglie un titolo in Documenti. */
    fun stile(s: StileParagrafo) {
        val a = selectionStart; val b = selectionEnd
        var m = modello()
        val toccati = m.paragrafiToccati(a, b)
        val inizi = IntArray(m.paragrafi.size)
        for (k in 1 until inizi.size) inizi[k] = inizi[k - 1] + m.paragrafi[k - 1].lunghezza + 1
        for (k in toccati) {
            m = m.formatta(inizi[k], inizi[k] + m.paragrafi[k].lunghezza) { f, _ -> f.copy(punti = null, colore = null) }
        }
        applicaModello(m.formattaParagrafi(a, b) { it.copy(stile = s) })
        if (pendente != null) pendente = pendente?.copy(punti = null, colore = null)
        notifica()
    }

    fun allineamento(al: Allineamento) = paragrafi { it.copy(allineamento = al) }

    /** Elenco puntato o numerato: se i paragrafi lo sono gia' tutti, lo toglie. */
    fun elenco(e: Elenco) {
        val tutti = modello().formato(selectionStart, selectionEnd).elenco == e
        paragrafi { it.copy(elenco = if (tutti) Elenco.NESSUNO else e, rientro = if (tutti) 0 else it.rientro) }
    }

    fun rientro(passo: Int) = paragrafi { it.copy(rientro = (it.rientro + passo).coerceIn(0, 8)) }

    private fun alterna(attivo: (Frammento) -> Boolean, imposta: (Frammento, Boolean) -> Frammento) {
        val a = selectionStart; val b = selectionEnd
        if (a == b) {
            val base = pendente ?: modello().formatoCursore(a)
            impostaPendente(imposta(base, !attivo(base)), a)
        } else {
            val m = modello()
            val acceso = m.formato(a, b).let { f -> attivo(Frammento("", f.grassetto, f.corsivo, f.sottolineato, f.barrato)) }
            applicaModello(m.formatta(a, b) { f, _ -> imposta(f, !acceso) })
        }
        notifica()
    }

    private fun caratteri(cambia: (Frammento, Paragrafo) -> Frammento) {
        val a = selectionStart; val b = selectionEnd
        val m = modello()
        if (a == b) {
            val k = m.paragrafiToccati(a, a).first
            impostaPendente(cambia(pendente ?: m.formatoCursore(a), m.paragrafi[k]).soloCarattere(), a)
        } else {
            applicaModello(m.formatta(a, b, cambia))
        }
        notifica()
    }

    private fun paragrafi(cambia: (Paragrafo) -> Paragrafo) {
        applicaModello(modello().formattaParagrafi(selectionStart, selectionEnd, cambia))
        notifica()
    }

    private fun impostaPendente(f: Frammento, pos: Int) {
        pendente = f
        pendenteDa = pos
        pendenteFino = pos
    }

    private fun applicaModello(m: TestoRicco) {
        normalizzando = true
        try {
            Impaginazione.applica(text, m)
        } finally {
            normalizzando = false
        }
        aggiornaGiustificazione(m)
        alCambioAltezza?.invoke()
    }

    /**
     * Android giustifica tutto il campo o niente: mentre si scrive, il campo giustifica solo se lo sono
     * tutti i paragrafi. Sulla pagina ogni paragrafo ha il suo allineamento (vedi Impaginazione.impagina).
     */
    private fun aggiornaGiustificazione(m: TestoRicco) {
        val modo = if (m.paragrafi.all { it.allineamento == Allineamento.GIUSTIFICATO }) LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        else LineBreaker.JUSTIFICATION_MODE_NONE
        if (justificationMode != modo) justificationMode = modo
    }

    // ---------------------------------------------------------------- scrittura

    /** Il testo scritto nel punto del formato pendente prende quel formato. */
    private fun conPendente(source: CharSequence, start: Int, end: Int, dstart: Int): CharSequence? {
        val p = pendente
        if (p == null || normalizzando || end <= start || dstart != pendenteDa) return null
        return SpannableStringBuilder(source, start, end).also {
            it.setSpan(SpanFrammento(p), 0, it.length, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }

    /** Il frammento prima del testo con il formato pendente si ferma dove quello comincia. */
    private fun staccaDalPrecedente(e: Editable) {
        val da = pendenteDa
        if (da < 0 || pendenteFino <= da) return
        for (s in e.getSpans(da, pendenteFino, SpanFrammento::class.java)) {
            val a = e.getSpanStart(s); val b = e.getSpanEnd(s)
            if (a < da && b > da) e.setSpan(s, a, da, e.getSpanFlags(s))
        }
    }

    /** Dentro [r] ci sono span di formato non di Tratto (testo incollato da un'altra app)? */
    private fun formatoEstraneo(e: Editable, r: IntRange): Boolean =
        e.getSpans(r.first, r.last + 1, CharacterStyle::class.java).any {
            it !is SpanFrammento && it !is SpanParagrafo && e.getSpanFlags(it) and Spanned.SPAN_COMPOSING == 0 &&
                it !is android.text.style.SuggestionSpan
        }

    /** "A capo" su una voce d'elenco vuota: come in Documenti, l'elenco finisce e non si va a capo. */
    private fun esciDallElencoVuoto(e: Editable, i: Int) {
        val inizio = e.lastIndexOf('\n', i - 1) + 1
        if (inizio != i) return
        val attributi = Impaginazione.attributiParagrafo(e, inizio, i)
        if (attributi.elenco == Elenco.NESSUNO) return
        e.delete(i, i + 1)
        val m = Impaginazione.modello(e)
        Impaginazione.applica(e, m.formattaParagrafi(inizio, inizio) { it.copy(elenco = Elenco.NESSUNO, rientro = 0) })
    }

    /**
     * Rimette uno span per paragrafo, giusto: dopo un "a capo" il paragrafo nuovo eredita dal vecchio,
     * unendo due paragrafi vince il primo, e gli elenchi numerati si rinumerano.
     */
    private fun normalizzaParagrafi(e: Editable) {
        val testo = e.toString()
        val paragrafi = ArrayList<Paragrafo>()
        var inizio = 0
        while (true) {
            val fine = testo.indexOf('\n', inizio).let { if (it < 0) testo.length else it }
            val attr = Impaginazione.attributiParagrafo(e, inizio, fine)
            paragrafi += attr.copy(frammenti = if (fine > inizio) listOf(Frammento(testo.substring(inizio, fine))) else emptyList())
            if (fine >= testo.length) break
            inizio = fine + 1
        }
        val m = TestoRicco(paragrafi)
        val voluti = Impaginazione.spanParagrafi(m, e.length)
        val attuali = e.getSpans(0, e.length, SpanParagrafo::class.java).sortedBy { e.getSpanStart(it) }
        val uguali = attuali.size == voluti.size && attuali.zip(voluti).all { (s, v) ->
            e.getSpanStart(s) == v.first.first && e.getSpanEnd(s) == v.first.last && s.uguale(v.second)
        }
        if (uguali) return
        attuali.forEach { e.removeSpan(it) }
        for ((r, s) in voluti) {
            e.setSpan(s, r.first, r.last, Spanned.SPAN_INCLUSIVE_INCLUSIVE or (16 shl Spanned.SPAN_PRIORITY_SHIFT))
        }
        aggiornaGiustificazione(m)
    }

    // ---------------------------------------------------------------- cursore e tasti

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (pendente != null && (selStart != selEnd || selStart !in pendenteDa..pendenteFino)) pendente = null
        notifica()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) alCambioAltezza?.invoke()
    }

    private fun notifica() {
        val f = alCambioFormato ?: return
        if (normalizzando) return
        f(formato())
    }

    /** Scorciatoie di Documenti con una tastiera fisica: Ctrl+B, Ctrl+I, Ctrl+U, Ctrl+\ e gli elenchi. */
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when {
                keyCode == KeyEvent.KEYCODE_B -> { grassetto(); return true }
                keyCode == KeyEvent.KEYCODE_I -> { corsivo(); return true }
                keyCode == KeyEvent.KEYCODE_U -> { sottolineato(); return true }
                keyCode == KeyEvent.KEYCODE_BACKSLASH -> { pulisci(); return true }
                keyCode == KeyEvent.KEYCODE_7 && event.isShiftPressed -> { elenco(Elenco.NUMERATO); return true }
                keyCode == KeyEvent.KEYCODE_8 && event.isShiftPressed -> { elenco(Elenco.PUNTATO); return true }
                keyCode == KeyEvent.KEYCODE_X && event.isShiftPressed && event.isAltPressed -> { barrato(); return true }
            }
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Tab e Maiusc+Tab su un elenco cambiano il rientro, come in Documenti.
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed) {
            val f = modello().formato(selectionStart, selectionEnd)
            if (f.elenco != Elenco.NESSUNO) {
                rientro(if (event.isShiftPressed) -1 else 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val ACCENTO = 0xFF6546F3.toInt()
    }
}
