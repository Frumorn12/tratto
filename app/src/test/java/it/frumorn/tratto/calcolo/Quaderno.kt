package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.scrittura.BellaScrittura
import it.frumorn.tratto.scrittura.Stile
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Una pagina finta per i test: simboli scritti con la bella scrittura (font Hershey) nelle
 * posizioni volute, piu' trattini e radici sintetici. Ricorda quale simbolo ha prodotto ogni
 * tratto, cosi' [LettoreFinto] puo' "leggere" come farebbe ML Kit.
 */
class Quaderno(val stile: Stile = Stile.STAMPATELLO, val altezzaX: Float = 16f) {
    companion object {
        const val NERO = 0xFF202020.toInt()

        fun preparaFont() {
            // I test girano nella cartella del modulo app: l'asset si legge dal sorgente.
            BellaScrittura.prepara(File("src/main/assets/${BellaScrittura.ASSET}").readBytes())
        }
    }

    private var prossimoId = 1L
    val tratti = ArrayList<Tratto>()

    /** Per ogni tratto: indice del simbolo e il suo testo. */
    val simboli = HashMap<Long, Pair<Int, String>>()
    private var quantiSimboli = 0

    fun nuovoId(): Long = prossimoId++

    /** Altezza delle cifre scritte con [scala] 1. */
    val hCifre: Float get() = Calcolatore.rapportoCifre(stile) * altezzaX

    fun registra(t: List<Tratto>, testo: String) {
        val i = quantiSimboli++
        for (x in t) simboli[x.id] = i to testo
        tratti += t
    }

    /**
     * Scrive [testo] un carattere alla volta da [x], con la linea di base a [base]; ogni carattere
     * e' un simbolo. Restituisce la x dove finisce la scritta.
     */
    fun scrivi(testo: String, x: Float, base: Float, scala: Float = 1f): Float {
        var cx = x
        for (c in testo) {
            val r = caso
            // Con il disordine ogni carattere ha grandezza, linea di base e spaziatura un po' diverse.
            val ax = altezzaX * scala * (if (r != null) 1f + (r.nextFloat() - 0.5f) * 0.25f else 1f)
            val b = base + (if (r != null) (r.nextFloat() - 0.5f) * 0.15f * hCifre * scala else 0f)
            if (c == ' ') { cx += 0.5f * ax; continue }
            val t = BellaScrittura.componi(c.toString(), stile, cx, b - BellaScrittura.ascesa(stile, ax), ax, 0f, NERO, Penna.PENNA, 3f) { nuovoId() }
            if (r != null) for (tr in t) for (i in 0 until tr.quanti) {
                tr.punti[i * Tratto.CAMPI] += (r.nextFloat() - 0.5f) * 0.6f
                tr.punti[i * Tratto.CAMPI + 1] += (r.nextFloat() - 0.5f) * 0.6f
            }
            registra(t, c.toString())
            cx += BellaScrittura.dimensioni(c.toString(), stile, ax, 0f)[0] + (if (r != null) 0.05f + 0.3f * r.nextFloat() else 0.1f) * ax
        }
        return cx
    }

    /** Se c'e', rende la scrittura irregolare come quella vera (vedi [scrivi]). */
    var caso: java.util.Random? = null

    /** Scrive [testo] tutto insieme (come parole in corsivo): un solo "simbolo" per ML Kit. */
    fun parole(testo: String, x: Float, base: Float): Float {
        val t = BellaScrittura.componi(testo, stile, x, base - BellaScrittura.ascesa(stile, altezzaX), altezzaX, 0f, NERO, Penna.PENNA, 3f) { nuovoId() }
        registra(t, testo)
        return x + BellaScrittura.dimensioni(testo, stile, altezzaX, 0f)[0]
    }

    /** Un tratto sintetico lungo la spezzata [punti] (x, y alternati), un punto ogni 1,5 unita'. */
    fun tratto(vararg punti: Float, testo: String? = null): Tratto {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += punti[0]; ys += punti[1]
        for (k in 1 until punti.size / 2) {
            val ax = punti[2 * k - 2]; val ay = punti[2 * k - 1]
            val bx = punti[2 * k]; val by = punti[2 * k + 1]
            val n = ceil(hypot(bx - ax, by - ay) / 1.5f).toInt().coerceAtLeast(1)
            for (j in 1..n) {
                xs += ax + (bx - ax) * j / n
                ys += ay + (by - ay) * j / n
            }
        }
        val p = FloatArray(xs.size * Tratto.CAMPI)
        for (i in xs.indices) {
            val o = i * Tratto.CAMPI
            p[o] = xs[i]; p[o + 1] = ys[i]; p[o + 2] = 0.5f; p[o + 3] = -1f; p[o + 4] = -1f; p[o + 5] = i * 8f
        }
        val t = Tratto(nuovoId(), Penna.PENNA, NERO, 3f, p)
        if (testo != null) registra(listOf(t), testo) else tratti += t
        return t
    }

    /** Un uguale di due trattini centrato sull'asse [asse]: i tratti non sono ancora sulla pagina. */
    fun uguale(x: Float, asse: Float, larghezza: Float = 0.8f * hCifre): List<Tratto> {
        val d = 0.18f * hCifre
        val a = trattoFuori(x, asse - d, x + larghezza, asse - d - 1f)
        val b = trattoFuori(x + 1f, asse + d, x + larghezza + 1f, asse + d)
        return listOf(a, b)
    }

    /** Come [tratto], ma senza aggiungerlo alla pagina (lo fa il test quando "finisce"). */
    fun trattoFuori(vararg punti: Float): Tratto {
        val t = tratto(*punti)
        tratti.remove(t)
        return t
    }

    /** Asse della riga con linea di base [base]: a meta' altezza delle cifre. */
    fun asse(base: Float): Float = base - hCifre / 2f
}

/** Legge i tratti come farebbe ML Kit, ma senza sbagliare: i testi dei simboli in fila. */
internal class LettoreFinto(private val q: Quaderno, private val sostituzioni: Map<String, List<String>> = emptyMap()) : Lettore {
    var chiamate = 0
    val letti = ArrayList<String>()

    override suspend fun leggi(tratti: List<Tratto>, preContesto: String, altezzaRiga: Float): List<String> {
        chiamate++
        val testo = tratti.mapNotNull { q.simboli[it.id] }.distinctBy { it.first }.joinToString("") { it.second }
        letti += testo
        return sostituzioni[testo] ?: listOf(testo)
    }
}
