package it.frumorn.tratto.editor

import androidx.ink.strokes.Stroke
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.ink.Pennelli
import java.util.concurrent.atomic.AtomicLong

/** Una pagina aperta nell'editor: i tratti salvati piu' la loro geometria di Ink, calcolata una volta. */
class PaginaViva(var pagina: Pagina) {
    val tratti = ArrayList<Tratto>()
    private val strokes = HashMap<Long, Stroke>()
    private val scatole = HashMap<Long, FloatArray>()
    @Volatile var caricata = false
    var sporca = false
    /** Cresce a ogni modifica: chi disegna la pagina sa quando rifare la cache. */
    var versione = 0
        private set

    fun stroke(t: Tratto): Stroke? = strokes.getOrPut(t.id) { Pennelli.stroke(t) ?: return null }

    /** Riquadro [minX, minY, maxX, maxY] del tratto, allargato di mezzo spessore. */
    fun scatola(t: Tratto): FloatArray = scatole.getOrPut(t.id) {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE; var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        val p = t.punti
        for (i in 0 until t.quanti) {
            val x = p[i * Tratto.CAMPI]; val y = p[i * Tratto.CAMPI + 1]
            if (x < a) a = x; if (y < b) b = y; if (x > c) c = x; if (y > d) d = y
        }
        val m = t.spessore * 1.6f
        floatArrayOf(a - m, b - m, c + m, d + m)
    }

    /** Riusa la geometria gia' calcolata da Ink per un tratto appena disegnato. */
    fun precarica(id: Long, s: Stroke) { strokes[id] = s }

    fun preparaGeometria() {
        for (t in tratti) stroke(t)
    }

    fun aggiungi(t: Tratto, indice: Int = tratti.size, stroke: Stroke? = null) {
        tratti.add(indice.coerceIn(0, tratti.size), t)
        if (stroke != null) strokes[t.id] = stroke
        modificata()
    }

    fun rimuovi(t: Tratto): Int {
        val i = tratti.indexOfFirst { it.id == t.id }
        if (i >= 0) {
            tratti.removeAt(i)
            strokes.remove(t.id)
            scatole.remove(t.id)
            modificata()
        }
        return i
    }

    private fun modificata() {
        sporca = true
        versione++
    }
}

/** Una modifica annullabile: su una pagina si tolgono dei tratti e se ne aggiungono altri. */
class Modifica(
    val pagina: PaginaViva,
    val tolti: List<Pair<Int, Tratto>>,
    val aggiunti: List<Tratto>,
)

/** Aggiunta o rimozione di una pagina intera. */
class ModificaPagine(val indice: Int, val pagina: PaginaViva, val aggiunta: Boolean)

/** La nota aperta nell'editor, con la cronologia per annullare e ripetere. */
class Documento(private val archivio: Archivio, info: NotaInfo) {
    var info: NotaInfo = info
        private set
    val pagine = ArrayList<PaginaViva>()
    private val indietro = ArrayDeque<Any>()
    private val avanti = ArrayDeque<Any>()
    private val prossimoId = AtomicLong(System.currentTimeMillis() * 1000)
    var pagineSporche = false
        private set

    /** Chiamato quando cambia la possibilita' di annullare/ripetere o l'elenco delle pagine. */
    var alCambio: (() -> Unit)? = null

    val puoAnnullare get() = indietro.isNotEmpty()
    val puoRipetere get() = avanti.isNotEmpty()

    /** Legge l'elenco delle pagine (veloce). I tratti si caricano con [caricaTratti]. */
    fun apri() {
        pagine.clear()
        archivio.pagine(info.id).forEach { pagine += PaginaViva(it) }
        if (pagine.isEmpty()) {
            pagine += PaginaViva(Pagina(Archivio.nuovoId(), info.sfondo))
            pagineSporche = true
        }
    }

    fun caricaTratti(p: PaginaViva) {
        if (p.caricata) return
        val letti = archivio.tratti(info.id, p.pagina.id)
        synchronized(p) {
            p.tratti.clear()
            p.tratti.addAll(letti)
            letti.maxOfOrNull { it.id }?.let { max -> prossimoId.updateAndGet { maxOf(it, max + 1) } }
            p.preparaGeometria()
            p.caricata = true
        }
    }

    fun nuovoIdTratto(): Long = prossimoId.incrementAndGet()

    fun esegui(m: Modifica) {
        applica(m, inverso = false)
        indietro.addLast(m)
        if (indietro.size > 200) indietro.removeFirst()
        avanti.clear()
        alCambio?.invoke()
    }

    /** Registra una modifica gia' applicata a mano (gomma e lazo lavorano dal vivo). */
    fun registra(m: Modifica) {
        if (m.tolti.isEmpty() && m.aggiunti.isEmpty()) return
        indietro.addLast(m)
        if (indietro.size > 200) indietro.removeFirst()
        avanti.clear()
        alCambio?.invoke()
    }

    fun annulla(): PaginaViva? {
        val m = indietro.removeLastOrNull() ?: return null
        avanti.addLast(m)
        val p = when (m) {
            is Modifica -> { applica(m, inverso = true); m.pagina }
            is ModificaPagine -> { applicaPagine(m, inverso = true); null }
            else -> null
        }
        alCambio?.invoke()
        return p
    }

    fun ripeti(): PaginaViva? {
        val m = avanti.removeLastOrNull() ?: return null
        indietro.addLast(m)
        val p = when (m) {
            is Modifica -> { applica(m, inverso = false); m.pagina }
            is ModificaPagine -> { applicaPagine(m, inverso = false); null }
            else -> null
        }
        alCambio?.invoke()
        return p
    }

    private fun applica(m: Modifica, inverso: Boolean) {
        val p = m.pagina
        if (!inverso) {
            m.tolti.forEach { (_, t) -> p.rimuovi(t) }
            m.aggiunti.forEach { p.aggiungi(it) }
        } else {
            m.aggiunti.forEach { p.rimuovi(it) }
            m.tolti.sortedBy { it.first }.forEach { (i, t) -> p.aggiungi(t, i) }
        }
    }

    fun aggiungiPagina(dopo: Int = pagine.lastIndex, sfondo: Sfondo? = null): PaginaViva {
        val base = pagine.getOrNull(dopo)?.pagina
        val nuova = PaginaViva(Pagina(Archivio.nuovoId(), sfondo ?: base?.sfondo ?: info.sfondo)).apply { caricata = true }
        val m = ModificaPagine(dopo + 1, nuova, aggiunta = true)
        applicaPagine(m, inverso = false)
        indietro.addLast(m)
        avanti.clear()
        alCambio?.invoke()
        return nuova
    }

    fun eliminaPagina(indice: Int) {
        if (pagine.size <= 1) return
        val m = ModificaPagine(indice, pagine[indice], aggiunta = false)
        applicaPagine(m, inverso = false)
        indietro.addLast(m)
        avanti.clear()
        alCambio?.invoke()
    }

    fun cambiaSfondo(indice: Int, sfondo: Sfondo) {
        val p = pagine.getOrNull(indice) ?: return
        p.pagina = p.pagina.copy(sfondo = sfondo)
        pagineSporche = true
        alCambio?.invoke()
    }

    private fun applicaPagine(m: ModificaPagine, inverso: Boolean) {
        val aggiungi = m.aggiunta != inverso
        if (aggiungi) pagine.add(m.indice.coerceIn(0, pagine.size), m.pagina) else pagine.remove(m.pagina)
        pagineSporche = true
    }

    val modificato: Boolean get() = pagineSporche || pagine.any { it.sporca }

    /** Salva su disco le pagine cambiate. Da chiamare fuori dal thread principale. */
    fun salva() {
        val daSalvare = pagine.filter { it.sporca }
        for (p in daSalvare) {
            val copia = synchronized(p) { ArrayList(p.tratti) }
            archivio.salvaTratti(info.id, p.pagina.id, copia)
            p.sporca = false
        }
        if (pagineSporche) {
            val elenco = pagine.map { it.pagina }
            archivio.salvaPagine(info.id, elenco)
            val vive = elenco.map { it.id }.toSet()
            archivio.pagineOrfane(info.id, vive)
            pagineSporche = false
        }
    }
}
