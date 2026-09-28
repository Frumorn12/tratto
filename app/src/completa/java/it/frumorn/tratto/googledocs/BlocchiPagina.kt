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
 * Divide una pagina in blocchi, dall'alto in basso.
 *
 * Tratti e immagini si raccolgono in fasce orizzontali: due elementi stanno nella stessa fascia se in
 * verticale si sovrappongono o distano meno di [DISTANZA]. Una riga di scrittura (con accenti,
 * puntini e il segno di uguale) e' una fascia; due righe separate dall'interlinea sono due fasce; un
 * disegno alto e' una fascia sola. Le caselle di testo sono blocchi a parte, messi in ordine con le
 * fasce secondo il loro bordo alto.
 */
internal object BlocchiPagina {
    /** Spazio vuoto in verticale che separa due fasce, in unita' di pagina (circa 4 punti su un A4). */
    const val DISTANZA = 7f

    /** Da aumentare quando cambia il modo di dividere o di disegnare i blocchi: si rifanno tutti. */
    const val VERSIONE = 1

    /**
     * I blocchi della pagina in ordine di lettura. [altezzaTesto] da' l'altezza di una casella di testo
     * impaginata (su Android con uno StaticLayout).
     */
    fun dividi(pagina: Pagina, tratti: List<Tratto>, oggetti: List<Oggetto>, altezzaTesto: (Testo) -> Float): List<BloccoPagina> {
        if (pagina.pdfPagina >= 0) return listOf(BloccoPagina.Intera("pdf", Riquadro(0f, 0f, pagina.larghezza, pagina.altezza)))

        class Elemento(val r: Riquadro, val tratto: Tratto?, val immagine: Immagine?)

        val elementi = ArrayList<Elemento>()
        for (t in tratti) riquadro(t)?.let { elementi += Elemento(it, t, null) }
        for (o in oggetti) if (o is Immagine) elementi += Elemento(Riquadro(o.x, o.y, o.x + o.larghezza, o.y + o.altezza), null, o)
        elementi.sortBy { it.r.alto }

        val blocchi = ArrayList<BloccoPagina>()
        var fascia = ArrayList<Elemento>()
        var basso = Float.NEGATIVE_INFINITY
        fun chiudi() {
            if (fascia.isEmpty()) return
            val r = fascia.map { it.r }.reduce(Riquadro::unito)
            val tr = fascia.mapNotNull { it.tratto }.sortedBy { it.id }
            val im = fascia.mapNotNull { it.immagine }
            // La chiave e' il primo tratto della riga: i tratti aggiunti dopo hanno id piu' grandi.
            val chiave = tr.firstOrNull()?.let { "t${it.id}" } ?: "i${im.minOf { it.id }}"
            blocchi += BloccoPagina.Disegno(chiave, r, tr, im)
            fascia = ArrayList()
        }
        for (e in elementi) {
            if (e.r.alto > basso + DISTANZA) chiudi()
            fascia += e
            basso = maxOf(basso, e.r.basso)
        }
        chiudi()

        for (o in oggetti) {
            if (o !is Testo || o.contenuto.vuoto) continue
            blocchi += BloccoPagina.Scritta("x${o.id}", Riquadro(o.x, o.y, o.x + o.larghezza, o.y + altezzaTesto(o)), o)
        }
        return blocchi.sortedWith(compareBy({ it.riquadro.alto }, { it.riquadro.sinistra }))
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
