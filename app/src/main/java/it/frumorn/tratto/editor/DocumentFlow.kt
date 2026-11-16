package it.frumorn.tratto.editor

import it.frumorn.tratto.data.LayoutPagina
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.UNITA_PER_PUNTO
import kotlin.math.max

/**
 * Marges et corps de page du mode [LayoutPagina.DOCUMENT] (feuille type Google Docs).
 * Les images restent des objets libres ; seul le texte `__corpo__` est ancré.
 */
object DocumentMargins {
    /** Id réservé du corps de page (et préfixe des suites de paragraphe). */
    const val ID_CORPO = "__corpo__"

    /** Suite d'un paragraphe coupé en fin de page précédente. */
    const val ID_CORPO_CONT = "__corpo_cont__"

    /** Marge type Docs : 72 pt → unités page. */
    val MARGINE = 72f * UNITA_PER_PUNTO

    fun estCorpo(id: String) = id == ID_CORPO || id == ID_CORPO_CONT

    fun estCorpo(o: Oggetto) = o is Testo && estCorpo(o.id)

    fun zone(pagina: Pagina): FloatArray {
        val m = MARGINE
        val x = m
        val y = m
        val w = (pagina.larghezza - 2f * m).coerceAtLeast(Testo.LARGHEZZA_MIN)
        val h = (pagina.altezza - 2f * m).coerceAtLeast(Pagination.ALTEZZA_MIN)
        return floatArrayOf(x, y, w, h)
    }

    fun corpo(pagina: Pagina, contenuto: TestoRicco, continua: Boolean = false): Testo {
        val z = zone(pagina)
        return Testo(if (continua) ID_CORPO_CONT else ID_CORPO, z[0], z[1], z[2], contenuto)
    }

    fun ancora(pagina: Pagina, t: Testo): Testo {
        val z = zone(pagina)
        return t.copy(x = z[0], y = z[1], larghezza = z[2])
    }
}

/**
 * Reflow d'une chaîne de pages DOCUMENT : découpe le texte riche aux frontières de ligne
 * pour tenir dans la zone utile, page après page.
 */
object DocumentFlow {

    /** Indices inclusifs de la chaîne DOCUMENT qui contient [indice], ou IntRange.EMPTY. */
    fun catena(pagine: List<PaginaViva>, indice: Int): IntRange {
        if (indice !in pagine.indices) return IntRange.EMPTY
        if (pagine[indice].pagina.layout != LayoutPagina.DOCUMENT) return IntRange.EMPTY
        var a = indice
        while (a > 0 && pagine[a - 1].pagina.layout == LayoutPagina.DOCUMENT) a--
        var b = indice
        while (b < pagine.lastIndex && pagine[b + 1].pagina.layout == LayoutPagina.DOCUMENT) b++
        return a..b
    }

    fun corpoDi(p: PaginaViva): Testo? = synchronized(p) {
        p.oggetti.filterIsInstance<Testo>().firstOrNull { DocumentMargins.estCorpo(it.id) }
    }

    /** Texte global de la chaîne (fusionne les suites de paragraphe). */
    fun concatena(pagine: List<PaginaViva>, catena: IntRange): TestoRicco {
        if (catena.isEmpty()) return TestoRicco.VUOTO
        var acc: TestoRicco? = null
        for (i in catena) {
            val t = corpoDi(pagine[i]) ?: continue
            val continua = t.id == DocumentMargins.ID_CORPO_CONT
            acc = when {
                acc == null -> t.contenuto
                continua -> unisciContinua(acc, t.contenuto)
                else -> TestoRicco(acc.paragrafi + t.contenuto.paragrafi).normalizzato()
            }
        }
        return acc ?: TestoRicco.VUOTO
    }

    /**
     * Comme [concatena], en remplaçant le corps de la page [indice] par [nuovo]
     * ([continua] = suite de paragraphe si l'id était [DocumentMargins.ID_CORPO_CONT]).
     */
    fun concatenaConSostituzione(
        pagine: List<PaginaViva>,
        catena: IntRange,
        indice: Int,
        nuovo: TestoRicco,
        continua: Boolean,
    ): TestoRicco {
        if (catena.isEmpty()) return nuovo
        var acc: TestoRicco? = null
        for (i in catena) {
            val (contenuto, cont) = if (i == indice) {
                nuovo to continua
            } else {
                val t = corpoDi(pagine[i]) ?: continue
                t.contenuto to (t.id == DocumentMargins.ID_CORPO_CONT)
            }
            acc = when {
                acc == null -> contenuto
                cont -> unisciContinua(acc, contenuto)
                else -> TestoRicco(acc.paragrafi + contenuto.paragrafi).normalizzato()
            }
        }
        return acc ?: nuovo
    }

    private fun unisciContinua(a: TestoRicco, b: TestoRicco): TestoRicco {
        if (a.vuoto) return b
        if (b.vuoto) return a
        val out = ArrayList(a.paragrafi)
        val last = out.last()
        val first = b.paragrafi.first()
        out[out.lastIndex] = last.copy(frammenti = last.frammenti + first.frammenti)
        out.addAll(b.paragrafi.drop(1))
        return TestoRicco(out).normalizzato()
    }

    /**
     * Découpe [contenuto] en tranches qui tiennent dans [altezzaMax] à largeur [larghezza].
     * [continua] est vrai si la tranche poursuit le paragraphe de la précédente.
     */
    fun spezza(contenuto: TestoRicco, larghezza: Float, altezzaMax: Float): List<TrancheCorpo> {
        if (contenuto.vuoto) return listOf(TrancheCorpo(TestoRicco.VUOTO, continua = false))
        val hMax = max(altezzaMax, Pagination.ALTEZZA_MIN)
        val casella = Testo(DocumentMargins.ID_CORPO, 0f, 0f, larghezza, contenuto)
        val imp = Pagination.impagina(casella)
        if (imp.altezza <= hMax + 0.5f) return listOf(TrancheCorpo(contenuto, continua = false))

        val out = ArrayList<TrancheCorpo>()
        var pageStart = 0
        var heightInPage = 0f
        var primaDellaPagina = true

        for ((layout, _) in imp.parti) {
            for (line in 0 until layout.lineCount) {
                val lineH = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
                val lineStart = layout.getLineStart(line)
                if (!primaDellaPagina && heightInPage + lineH > hMax + 0.5f) {
                    val fine = lineStart.coerceIn(pageStart, contenuto.lunghezza)
                    if (fine > pageStart || out.isEmpty()) {
                        out += tranche(contenuto, pageStart, fine, out.isNotEmpty())
                    }
                    pageStart = fine
                    heightInPage = 0f
                    primaDellaPagina = true
                }
                heightInPage += lineH
                primaDellaPagina = false
            }
        }
        if (pageStart < contenuto.lunghezza || out.isEmpty()) {
            out += tranche(contenuto, pageStart, contenuto.lunghezza, out.isNotEmpty())
        }
        return out.ifEmpty { listOf(TrancheCorpo(contenuto, continua = false)) }
    }

    private fun tranche(t: TestoRicco, inizio: Int, fine: Int, puoContinuare: Boolean): TrancheCorpo {
        val slice = t.sezione(inizio, fine)
        val continua = puoContinuare && inizio > 0 && !estFrontieraParagrafo(t, inizio)
        return TrancheCorpo(slice, continua)
    }

    /** Vrai si [offset] est au début d'un paragraphe (juste après un \\n, ou 0). */
    private fun estFrontieraParagrafo(t: TestoRicco, offset: Int): Boolean {
        if (offset <= 0) return true
        val s = t.testoSemplice
        if (offset >= s.length) return true
        return s[offset - 1] == '\n'
    }
}

data class TrancheCorpo(val contenuto: TestoRicco, val continua: Boolean)
