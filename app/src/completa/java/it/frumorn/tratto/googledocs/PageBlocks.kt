package it.frumorn.tratto.googledocs

import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.Tratto
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Un rettangolo in unita' di pagina (larga 1000, y verso il basso). */
internal data class Riquadro(val sinistra: Float, val alto: Float, val destra: Float, val basso: Float) {
    val larghezza get() = destra - sinistra
    val altezza get() = basso - alto

    fun unito(o: Riquadro) = Riquadro(minOf(sinistra, o.sinistra), minOf(alto, o.alto), maxOf(destra, o.destra), maxOf(basso, o.basso))
}

/**
 * Un pezzo di pagina che nel documento Google diventa un paragrafo a se': una riga scritta a mano,
 * una formula, un disegno (immagine trasparente ritagliata), oppure una casella di testo (testo vero).
 * [chiave] e' unica nella pagina e resta la stessa finche' il pezzo non cambia natura: aggiungere un
 * tratto a una riga la ridisegna, non la sposta.
 */
internal sealed interface BloccoPagina {
    val chiave: String
    val riquadro: Riquadro

    /** Tratti e immagini che si toccano in verticale: diventano un PNG trasparente. */
    data class Disegno(override val chiave: String, override val riquadro: Riquadro, val tratti: List<Tratto>, val immagini: List<Immagine>) : BloccoPagina

    /** Una casella di testo: diventa testo vero, con il suo formato. */
    data class Scritta(override val chiave: String, override val riquadro: Riquadro, val testo: Testo) : BloccoPagina

    /** Una pagina di PDF: resta un'immagine intera, con il PDF sotto e tutto quello che c'e' sopra. */
    data class Intera(override val chiave: String, override val riquadro: Riquadro) : BloccoPagina
}

/**
 * Divide una pagina in blocchi, in ordine di lettura: ogni scritta e ogni disegno diventa un blocco a
 * se', cosi' nel documento un disegno accanto al testo non si porta dietro le righe.
 *
 * 1. Gruppi: tratti e immagini che si toccano o quasi (meno di [VICINO_X] di lato, [VICINO_Y] sopra o
 *    sotto) stanno insieme. Le lettere di una parola, gli accenti, il segno di uguale, un esponente
 *    sono un gruppo; due parole lontane o due righe separate dall'interlinea no.
 * 2. Disegni: un gruppo con un'immagine, o molto piu' alto di una riga di scrittura (vedi
 *    [ALTEZZA_DISEGNO]), e' un blocco da solo.
 * 3. Righe: gli altri gruppi allineati in orizzontale (che si sovrappongono in verticale per almeno
 *    [SOVRAPPOSIZIONE] del piu' basso) sono una riga. I puntini rimasti soli vanno nella riga vicina.
 *
 * Le caselle di testo sono blocchi a parte. L'ordine e' dall'alto in basso; blocchi che cominciano
 * quasi alla stessa altezza (entro [STESSA_ALTEZZA]) vanno da sinistra a destra.
 */
internal object PageBlocks {
    /** Distanza sotto cui due tratti stanno nello stesso gruppo, in unita' di pagina (1 unita' ~ 0,2 mm). */
    const val VICINO_X = 25f
    const val VICINO_Y = 12f

    /** Un gruppo piu' alto di cosi' (e di tre volte l'altezza tipica dei gruppi della pagina) e' un disegno. */
    const val ALTEZZA_DISEGNO = 150f

    /** Quanto due gruppi devono sovrapporsi in verticale per stare nella stessa riga (frazione del piu' basso). */
    const val SOVRAPPOSIZIONE = 0.5f

    /** Un gruppo piu' piccolo di cosi' in tutte e due le direzioni e' un puntino (accento, punto, virgola). */
    const val PUNTINO = 18f

    /** Distanza massima tra un puntino e la riga a cui si attacca. */
    const val DISTANZA_PUNTINO = 40f

    const val STESSA_ALTEZZA = 20f

    /** Da aumentare quando cambia il modo di dividere o di disegnare i blocchi: si rifanno tutti. */
    const val VERSIONE = 2

    private class Elemento(val r: Riquadro, val tratto: Tratto?, val immagine: Immagine?)

    private class Gruppo(val elementi: List<Elemento>) {
        val r = elementi.map { it.r }.reduce(Riquadro::unito)
        val conImmagine = elementi.any { it.immagine != null }
    }

    /**
     * I blocchi della pagina in ordine di lettura. [altezzaTesto] da' l'altezza di una casella di testo
     * impaginata (su Android con uno StaticLayout).
     */
    fun dividi(pagina: Pagina, tratti: List<Tratto>, oggetti: List<Oggetto>, altezzaTesto: (Testo) -> Float): List<BloccoPagina> {
        if (pagina.pdfPagina >= 0) return listOf(BloccoPagina.Intera("pdf", Riquadro(0f, 0f, pagina.larghezza, pagina.altezza)))

        val elementi = ArrayList<Elemento>()
        for (t in tratti) riquadro(t)?.let { elementi += Elemento(it, t, null) }
        for (o in oggetti) if (o is Immagine) elementi += Elemento(Riquadro(o.x, o.y, o.x + o.larghezza, o.y + o.altezza), null, o)

        val gruppi = gruppi(elementi)
        val altezze = gruppi.filter { !it.conImmagine }.map { it.r.altezza }.sorted()
        val tipica = if (altezze.size >= 3) altezze[altezze.size / 2] else 0f
        val soglia = maxOf(ALTEZZA_DISEGNO, 3 * tipica)
        val (disegni, scritte) = gruppi.partition { it.conImmagine || it.r.altezza > soglia }

        val blocchi = ArrayList<BloccoPagina>()
        for (g in disegni) blocchi += blocco(g.elementi, g.r)
        for (riga in righe(scritte)) blocchi += blocco(riga.flatMap { it.elementi }, riga.map { it.r }.reduce(Riquadro::unito))
        for (o in oggetti) {
            if (o !is Testo || o.contenuto.vuoto) continue
            blocchi += BloccoPagina.Scritta("x${o.id}", Riquadro(o.x, o.y, o.x + o.larghezza, o.y + altezzaTesto(o)), o)
        }
        return inOrdine(blocchi)
    }

    /** Tratti e immagini che si toccano o quasi, uniti a catena (union-find). */
    private fun gruppi(e: List<Elemento>): List<Gruppo> {
        val padre = IntArray(e.size) { it }
        fun radice(i: Int): Int {
            var x = i
            while (padre[x] != x) {
                padre[x] = padre[padre[x]]
                x = padre[x]
            }
            return x
        }
        // In ordine di bordo sinistro: si confrontano solo gli elementi che possono toccarsi in orizzontale.
        val ordine = e.indices.sortedBy { e[it].r.sinistra }
        for ((a, i) in ordine.withIndex()) {
            val ri = e[i].r
            for (b in a + 1 until ordine.size) {
                val j = ordine[b]
                val rj = e[j].r
                if (rj.sinistra > ri.destra + VICINO_X) break
                if (rj.alto <= ri.basso + VICINO_Y && ri.alto <= rj.basso + VICINO_Y) padre[radice(i)] = radice(j)
            }
        }
        return e.indices.groupBy { radice(it) }.values.map { ind -> Gruppo(ind.map { e[it] }) }
    }

    /** Gruppi allineati in orizzontale, dall'alto in basso; i puntini soli vanno nella riga piu' vicina. */
    private fun righe(gruppi: List<Gruppo>): List<List<Gruppo>> {
        val (puntini, veri) = gruppi.partition { it.r.larghezza < PUNTINO && it.r.altezza < PUNTINO }
        class Riga(g: Gruppo) {
            val gruppi = arrayListOf(g)
            var r = g.r
        }
        val righe = ArrayList<Riga>()
        for (g in veri.sortedBy { it.r.alto }) {
            val riga = righe.firstOrNull { sovrapposizione(it.r, g.r) >= SOVRAPPOSIZIONE * minOf(it.r.altezza, g.r.altezza) }
            if (riga == null) righe += Riga(g) else {
                riga.gruppi += g
                riga.r = riga.r.unito(g.r)
            }
        }
        for (p in puntini) {
            val vicina = righe.minByOrNull { distanza(it.r, p.r) }?.takeIf { distanza(it.r, p.r) <= DISTANZA_PUNTINO }
            if (vicina == null) righe += Riga(p) else vicina.gruppi += p
        }
        return righe.map { it.gruppi }
    }

    private fun sovrapposizione(a: Riquadro, b: Riquadro) = minOf(a.basso, b.basso) - maxOf(a.alto, b.alto)

    private fun distanza(a: Riquadro, b: Riquadro): Float {
        val dx = maxOf(0f, maxOf(a.sinistra, b.sinistra) - minOf(a.destra, b.destra))
        val dy = maxOf(0f, maxOf(a.alto, b.alto) - minOf(a.basso, b.basso))
        return kotlin.math.hypot(dx, dy)
    }

    private fun blocco(el: List<Elemento>, r: Riquadro): BloccoPagina.Disegno {
        val tr = el.mapNotNull { it.tratto }.sortedBy { it.id }
        val im = el.mapNotNull { it.immagine }
        // La chiave e' il tratto piu' vecchio: i tratti aggiunti dopo hanno id piu' grandi.
        val chiave = tr.firstOrNull()?.let { "t${it.id}" } ?: "i${im.minOf { it.id }}"
        return BloccoPagina.Disegno(chiave, r, tr, im)
    }

    /** Dall'alto in basso; quelli che cominciano quasi alla stessa altezza, da sinistra a destra. */
    private fun inOrdine(blocchi: List<BloccoPagina>): List<BloccoPagina> {
        val out = ArrayList<BloccoPagina>()
        var fila = ArrayList<BloccoPagina>()
        for (b in blocchi.sortedWith(compareBy({ it.riquadro.alto }, { it.riquadro.sinistra }))) {
            if (fila.isNotEmpty() && b.riquadro.alto - fila[0].riquadro.alto > STESSA_ALTEZZA) {
                out += fila.sortedBy { it.riquadro.sinistra }
                fila = ArrayList()
            }
            fila += b
        }
        out += fila.sortedBy { it.riquadro.sinistra }
        return out
    }

    /** L'ingombro di un tratto con il suo spessore (la pressione lo allarga fino a circa 1,35 volte), o null se non ha punti. */
    fun riquadro(t: Tratto): Riquadro? {
        val p = t.punti
        if (p.size < Tratto.CAMPI) return null
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < p.size) {
            val x = p[i]
            val y = p[i + 1]
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
            i += Tratto.CAMPI
        }
        val m = t.spessore * 0.75f + 1.5f
        return Riquadro(x0 - m, y0 - m, x1 + m, y1 + m)
    }

    /**
     * Impronta di un disegno che non cambia se lo si sposta: punti rispetto all'angolo del riquadro.
     * Nel documento il disegno sta dove sta (vedi [DrawingPlan]); se cambia solo il posto non si ridisegna.
     */
    fun improntaRelativa(b: BloccoPagina.Disegno): String = hash { d ->
        d.writeInt(VERSIONE)
        d.writeFloat(b.riquadro.larghezza); d.writeFloat(b.riquadro.altezza)
        d.writeInt(b.tratti.size)
        for (t in b.tratti) {
            d.writeInt(t.penna.ordinal)
            d.writeInt(t.colore)
            d.writeFloat(t.spessore)
            d.writeInt(t.punti.size)
            for ((i, f) in t.punti.withIndex()) {
                d.writeFloat(when (i % Tratto.CAMPI) { 0 -> f - b.riquadro.sinistra; 1 -> f - b.riquadro.alto; else -> f })
            }
        }
        for (im in b.immagini) {
            scrivi(d, im.file)
            d.writeFloat(im.x - b.riquadro.sinistra); d.writeFloat(im.y - b.riquadro.alto)
            d.writeFloat(im.larghezza); d.writeFloat(im.altezza)
        }
    }

    /** Impronta di quello che si disegna in un blocco: se non cambia, l'immagine nel documento e' ancora giusta. */
    fun impronta(pagina: Pagina, b: BloccoPagina, tratti: List<Tratto>, oggetti: List<Oggetto>): String = hash { d ->
        d.writeInt(VERSIONE)
        scrivi(d, b.javaClass.simpleName)
        when (b) {
            is BloccoPagina.Disegno -> {
                scriviRiquadro(d, b.riquadro)
                scriviTratti(d, b.tratti)
                for (im in b.immagini) scrivi(d, im.toString())
            }
            is BloccoPagina.Scritta -> scrivi(d, b.testo.contenuto.toString())
            is BloccoPagina.Intera -> {
                scrivi(d, pagina.toString())
                scriviTratti(d, tratti)
                for (o in oggetti) scrivi(d, o.toString())
            }
        }
    }

    /** Una stringa di qualunque lunghezza (writeUTF si ferma a 64 KB). */
    fun scrivi(d: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeInt(b.size)
        d.write(b)
    }

    private fun scriviRiquadro(d: DataOutputStream, r: Riquadro) {
        d.writeFloat(r.sinistra); d.writeFloat(r.alto); d.writeFloat(r.destra); d.writeFloat(r.basso)
    }

    private fun scriviTratti(d: DataOutputStream, tratti: List<Tratto>) {
        d.writeInt(tratti.size)
        for (t in tratti) {
            d.writeLong(t.id)
            d.writeInt(t.penna.ordinal)
            d.writeInt(t.colore)
            d.writeFloat(t.spessore)
            d.writeInt(t.punti.size)
            for (f in t.punti) d.writeFloat(f)
        }
    }

    fun hash(scrivi: (DataOutputStream) -> Unit): String {
        val md = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), md)).use(scrivi)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
