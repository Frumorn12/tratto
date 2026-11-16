package it.frumorn.tratto.editor

import androidx.ink.strokes.Stroke
import it.frumorn.tratto.data.Archive
import it.frumorn.tratto.data.LayoutPagina
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.ink.Brushes
import java.util.concurrent.atomic.AtomicLong

/**
 * Una pagina aperta nell'editor: i tratti salvati piu' la loro geometria di Ink, calcolata una volta,
 * e gli oggetti (immagini e caselle di testo) che stanno sotto i tratti.
 */
class PaginaViva(var pagina: Pagina) {
    val tratti = ArrayList<Tratto>()
    /** Nell'ordine di disegno: il primo sta sotto. */
    val oggetti = ArrayList<Oggetto>()
    private val strokes = HashMap<Long, Stroke>()
    private val scatole = HashMap<Long, FloatArray>()
    @Volatile var caricata = false
    var sporca = false
    /** Gli oggetti vanno salvati (sono in un file a parte, i tratti non si riscrivono per questo). */
    var oggettiSporchi = false
    /** Cresce a ogni modifica: chi disegna la pagina sa quando rifare la cache. */
    var versione = 0
        private set

    fun stroke(t: Tratto): Stroke? = strokes.getOrPut(t.id) { Brushes.stroke(t) ?: return null }

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

    fun indiceOggetto(id: String): Int = oggetti.indexOfFirst { it.id == id }

    fun oggetto(id: String): Oggetto? = oggetti.find { it.id == id }

    fun aggiungiOggetto(o: Oggetto, indice: Int = oggetti.size) {
        oggetti.add(indice.coerceIn(0, oggetti.size), o)
        oggettiModificati()
    }

    fun rimuoviOggetto(id: String): Int {
        val i = indiceOggetto(id)
        if (i >= 0) {
            oggetti.removeAt(i)
            oggettiModificati()
        }
        return i
    }

    fun sostituisciOggetti(nuovi: List<Oggetto>) {
        oggetti.clear()
        oggetti.addAll(nuovi)
        oggettiModificati()
    }

    private fun modificata() {
        sporca = true
        versione++
    }

    private fun oggettiModificati() {
        oggettiSporchi = true
        versione++
    }

    /** Après une rotation : jette les strokes Ink calculés dans l'ancien repère. */
    fun svuotaGeometria() {
        strokes.clear()
        scatole.clear()
        versione++
    }
}

/**
 * Una modifica annullabile: su una pagina si tolgono dei tratti e se ne aggiungono altri; lo stesso
 * per gli oggetti, con la loro posizione nell'ordine di disegno. Un oggetto spostato o cambiato e' tolto
 * e rimesso con lo stesso id, di solito allo stesso posto.
 */
class Modifica(
    val pagina: PaginaViva,
    val tolti: List<Pair<Int, Tratto>>,
    val aggiunti: List<Tratto>,
    val oggettiTolti: List<Pair<Int, Oggetto>> = emptyList(),
    /** Oggetti da inserire, con il posto (nell'elenco finale) in cui vanno. */
    val oggettiAggiunti: List<Pair<Int, Oggetto>> = emptyList(),
) {
    val vuota: Boolean get() = tolti.isEmpty() && aggiunti.isEmpty() && oggettiTolti.isEmpty() && oggettiAggiunti.isEmpty()
}

/** Aggiunta o rimozione di una pagina intera. */
class ModificaPagine(val indice: Int, val pagina: PaginaViva, val aggiunta: Boolean)

/** Rotation de page (90° × [quarti] horaire) : dimensions + encre + objets, annulable. */
class ModificaRotazione(
    val pagina: PaginaViva,
    val prima: Pagina,
    val dopo: Pagina,
    val trattiPrima: List<Tratto>,
    val trattiDopo: List<Tratto>,
    val oggettiPrima: List<Oggetto>,
    val oggettiDopo: List<Oggetto>,
)

/**
 * Remplacement d'une plage de pages (reflow document / bascule de layout) : métadonnées + objets
 * par page, avec éventuelle insertion ou suppression de [PaginaViva] dans la note.
 */
class ModificaFlussoDocumento(
    val indice: Int,
    val prima: List<StatoPaginaFlusso>,
    val dopo: List<StatoPaginaFlusso>,
)

/** État d'une page dans un [ModificaFlussoDocumento]. */
class StatoPaginaFlusso(
    val viva: PaginaViva,
    val meta: Pagina,
    val oggetti: List<Oggetto>,
)

/** La nota aperta nell'editor, con la cronologia per annullare e ripetere. */
class Document(private val archivio: Archive, info: NotaInfo) {
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
            pagine += PaginaViva(Pagina(Archive.nuovoId(), info.sfondo))
            pagineSporche = true
        }
    }

    /** Carica tratti e oggetti della pagina (se non l'ha gia' fatto). */
    fun caricaTratti(p: PaginaViva) {
        if (p.caricata) return
        val letti = archivio.tratti(info.id, p.pagina.id)
        val oggetti = archivio.oggetti(info.id, p.pagina.id)
        synchronized(p) {
            p.tratti.clear()
            p.tratti.addAll(letti)
            p.oggetti.clear()
            p.oggetti.addAll(oggetti)
            letti.maxOfOrNull { it.id }?.let { max -> prossimoId.updateAndGet { maxOf(it, max + 1) } }
            p.preparaGeometria()
            p.caricata = true
        }
    }

    fun nuovoIdTratto(): Long = prossimoId.incrementAndGet()

    /** File di un'immagine della nota (percorso relativo salvato nell'oggetto). */
    fun fileImmagine(file: String) = archivio.fileImmagine(info.id, file)

    /** Salva nella nota un'immagine nuova: vedi [Archive.salvaImmagine]. Da chiamare fuori dal thread principale. */
    fun salvaImmagine(estensione: String, scrivi: (java.io.OutputStream) -> Unit): String = archivio.salvaImmagine(info.id, estensione, scrivi)

    fun esegui(m: Modifica) {
        applica(m, inverso = false)
        indietro.addLast(m)
        if (indietro.size > 200) indietro.removeFirst()
        avanti.clear()
        alCambio?.invoke()
    }

    /** Registra una modifica gia' applicata a mano (gomma e lazo lavorano dal vivo). */
    fun registra(m: Modifica) {
        if (m.vuota) return
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
            is ModificaRotazione -> { applicaRotazione(m, inverso = true); m.pagina }
            is ModificaFlussoDocumento -> { applicaFlusso(m, inverso = true); m.prima.firstOrNull()?.viva }
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
            is ModificaRotazione -> { applicaRotazione(m, inverso = false); m.pagina }
            is ModificaFlussoDocumento -> { applicaFlusso(m, inverso = false); m.dopo.firstOrNull()?.viva }
            else -> null
        }
        alCambio?.invoke()
        return p
    }

    private fun applica(m: Modifica, inverso: Boolean) {
        val p = m.pagina
        synchronized(p) {
            if (!inverso) {
                m.tolti.forEach { (_, t) -> p.rimuovi(t) }
                m.aggiunti.forEach { p.aggiungi(it) }
                m.oggettiTolti.forEach { (_, o) -> p.rimuoviOggetto(o.id) }
                m.oggettiAggiunti.sortedBy { it.first }.forEach { (i, o) -> p.aggiungiOggetto(o, i) }
            } else {
                m.aggiunti.forEach { p.rimuovi(it) }
                m.tolti.sortedBy { it.first }.forEach { (i, t) -> p.aggiungi(t, i) }
                m.oggettiAggiunti.forEach { (_, o) -> p.rimuoviOggetto(o.id) }
                m.oggettiTolti.sortedBy { it.first }.forEach { (i, o) -> p.aggiungiOggetto(o, i) }
            }
        }
    }

    fun aggiungiPagina(dopo: Int = pagine.lastIndex, sfondo: Sfondo? = null): PaginaViva {
        val base = pagine.getOrNull(dopo)?.pagina
        val layout = base?.layout ?: LayoutPagina.CANVAS
        val nuova = PaginaViva(
            Pagina(
                Archive.nuovoId(),
                sfondo ?: base?.sfondo ?: info.sfondo,
                larghezza = base?.larghezza ?: it.frumorn.tratto.data.Foglio.LARGHEZZA,
                altezza = base?.altezza ?: it.frumorn.tratto.data.Foglio.ALTEZZA,
                layout = layout,
            ),
        ).apply {
            caricata = true
            if (layout == LayoutPagina.DOCUMENT) {
                oggetti += DocumentMargins.corpo(pagina, TestoRicco.VUOTO)
                oggettiSporchi = true
            }
        }
        val m = ModificaPagine(dopo + 1, nuova, aggiunta = true)
        applicaPagine(m, inverso = false)
        indietro.addLast(m)
        avanti.clear()
        alCambio?.invoke()
        return nuova
    }

    /**
     * Tourne la page [indice] de [quarti] × 90° dans le sens horaire (passer -1 pour l'antihoraire).
     * L'encre et les objets suivent ; largeur et hauteur sont échangées si besoin.
     */
    fun ruotaPagina(indice: Int, quarti: Int) {
        val q = ((quarti % 4) + 4) % 4
        if (q == 0) return
        val p = pagine.getOrNull(indice) ?: return
        caricaTratti(p)
        val prima = p.pagina
        val trattiPrima: List<Tratto>
        val oggettiPrima: List<Oggetto>
        synchronized(p) {
            trattiPrima = ArrayList(p.tratti)
            oggettiPrima = ArrayList(p.oggetti)
        }
        val trattiDopo = trattiPrima.map { ruotaTratto(it, prima.larghezza, prima.altezza, q) }
        val dopo = ruotaPaginaMeta(prima, q)
        val oggettiDopo = oggettiPrima.map { o ->
            val r = ruotaOggetto(o, prima.larghezza, prima.altezza, q)
            if (dopo.layout == LayoutPagina.DOCUMENT && r is Testo && DocumentMargins.estCorpo(r.id)) {
                DocumentMargins.ancora(dopo, r)
            } else r
        }
        val m = ModificaRotazione(p, prima, dopo, trattiPrima, trattiDopo, oggettiPrima, oggettiDopo)
        applicaRotazione(m, inverso = false)
        indietro.addLast(m)
        if (indietro.size > 200) indietro.removeFirst()
        avanti.clear()
        alCambio?.invoke()
    }

    private fun applicaRotazione(m: ModificaRotazione, inverso: Boolean) {
        val p = m.pagina
        val meta = if (inverso) m.prima else m.dopo
        val tratti = if (inverso) m.trattiPrima else m.trattiDopo
        val oggetti = if (inverso) m.oggettiPrima else m.oggettiDopo
        synchronized(p) {
            p.tratti.clear()
            p.oggetti.clear()
            p.tratti.addAll(tratti)
            p.oggetti.addAll(oggetti)
            p.pagina = meta
            p.sporca = true
            p.oggettiSporchi = true
            p.svuotaGeometria()
        }
        pagineSporche = true
    }

    /**
     * Pagine del documento collegato (Google Docs): almeno [quante], tutte alte [altezza]. Non entra
     * nella cronologia: le pagine seguono il documento, non l'utente.
     */
    fun adattaPagine(quante: Int, altezza: Float): Boolean {
        var cambiato = false
        for (p in pagine) {
            if (kotlin.math.abs(p.pagina.altezza - altezza) > 0.5f) {
                p.pagina = p.pagina.copy(altezza = altezza)
                cambiato = true
            }
        }
        while (pagine.size < quante) {
            pagine += PaginaViva(Pagina(Archive.nuovoId(), Sfondo.BIANCO, altezza = altezza)).apply { caricata = true }
            cambiato = true
        }
        if (cambiato) {
            pagineSporche = true
            alCambio?.invoke()
        }
        return cambiato
    }

    /**
     * Sposta (o toglie) tratti perche' il documento collegato ha mosso il loro disegno. Non entra nella
     * cronologia: e' una modifica venuta da fuori. Vero se e' cambiato qualcosa.
     */
    fun spostaTratti(s: SpostamentoTratti): Boolean {
        val da = pagine.getOrNull(s.da) ?: return false
        caricaTratti(da)
        val presi = synchronized(da) { da.tratti.filter { it.id in s.ids } }
        if (presi.isEmpty()) return false
        val a = if (s.a >= 0) pagine.getOrNull(s.a) ?: return false else null
        a?.let { caricaTratti(it) }
        synchronized(da) { presi.forEach { da.rimuovi(it) } }
        if (a != null) {
            val spostati = presi.map { t ->
                val p = t.punti.copyOf()
                var i = 0
                while (i + 1 < p.size) {
                    p[i] += s.dx
                    p[i + 1] += s.dy
                    i += Tratto.CAMPI
                }
                Tratto(t.id, t.penna, t.colore, t.spessore, p)
            }
            synchronized(a) { spostati.forEach { a.aggiungi(it) } }
        }
        return true
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

    /** Bascule la page [indice] en mode document ou canvas libre. */
    fun cambiaLayout(indice: Int, layout: LayoutPagina) {
        val p = pagine.getOrNull(indice) ?: return
        if (p.pagina.layout == layout) return
        caricaTratti(p)
        if (layout == LayoutPagina.DOCUMENT) {
            val oggettiPrima = synchronized(p) { ArrayList(p.oggetti) }
            val metaPrima = p.pagina
            val esistente = oggettiPrima.filterIsInstance<Testo>().firstOrNull { DocumentMargins.estCorpo(it.id) }
            val promuovi = oggettiPrima.filterIsInstance<Testo>()
                .filter { !DocumentMargins.estCorpo(it.id) }
                .maxByOrNull { it.contenuto.lunghezza }
            val contenuto = esistente?.contenuto ?: promuovi?.contenuto ?: TestoRicco.VUOTO
            val metaDoc = metaPrima.copy(layout = LayoutPagina.DOCUMENT)
            val z = DocumentMargins.zone(metaDoc)
            val tranches = DocumentFlow.spezza(contenuto, z[2], z[3])
            val n = tranches.size.coerceAtLeast(1)
            val statiPrima = listOf(StatoPaginaFlusso(p, metaPrima, oggettiPrima))
            val statiDopo = ArrayList<StatoPaginaFlusso>()
            for (k in 0 until n) {
                val v = if (k == 0) p else PaginaViva(
                    Pagina(
                        Archive.nuovoId(),
                        metaDoc.sfondo,
                        larghezza = metaDoc.larghezza,
                        altezza = metaDoc.altezza,
                        layout = LayoutPagina.DOCUMENT,
                    ),
                ).apply { caricata = true }
                val meta = if (k == 0) metaDoc else v.pagina
                val base = if (k == 0) {
                    ArrayList(oggettiPrima.filterNot {
                        it is Testo && (DocumentMargins.estCorpo(it.id) || (promuovi != null && it.id == promuovi.id))
                    })
                } else ArrayList()
                val tr = tranches.getOrElse(k) { TrancheCorpo(TestoRicco.VUOTO, continua = false) }
                base.add(0, DocumentMargins.corpo(meta, tr.contenuto, tr.continua))
                statiDopo += StatoPaginaFlusso(v, meta, base)
            }
            registraFlusso(ModificaFlussoDocumento(indice, statiPrima, statiDopo))
        } else {
            // DOCUMENT → CANVAS : le corps de cette page devient une boîte normale (pas toute la chaîne).
            val oggettiPrima = synchronized(p) { ArrayList(p.oggetti) }
            val metaPrima = p.pagina
            val corpo = oggettiPrima.filterIsInstance<Testo>().firstOrNull { DocumentMargins.estCorpo(it.id) }
            val oggettiDopo = ArrayList(oggettiPrima)
            if (corpo != null) {
                val i = oggettiDopo.indexOfFirst { it.id == corpo.id }
                if (i >= 0) oggettiDopo[i] = corpo.copy(id = Archive.nuovoId())
            }
            val metaDopo = metaPrima.copy(layout = LayoutPagina.CANVAS)
            registraFlusso(
                ModificaFlussoDocumento(
                    indice,
                    listOf(StatoPaginaFlusso(p, metaPrima, oggettiPrima)),
                    listOf(StatoPaginaFlusso(p, metaDopo, oggettiDopo)),
                ),
            )
        }
    }

    /**
     * Réécrit le corps de la chaîne DOCUMENT contenant [indice] à partir de [contenuto] global,
     * en créant ou supprimant des pages DOCUMENT si besoin.
     */
    fun reflowCatena(indice: Int, contenuto: TestoRicco) {
        val catena = DocumentFlow.catena(pagine, indice)
        if (catena.isEmpty()) return
        for (i in catena) caricaTratti(pagine[i])

        val primaPage = pagine[catena.first]
        val z = DocumentMargins.zone(primaPage.pagina)
        val tranches = DocumentFlow.spezza(contenuto, z[2], z[3])
        val n = tranches.size.coerceAtLeast(1)

        val statiPrima = catena.map { i ->
            val v = pagine[i]
            StatoPaginaFlusso(v, v.pagina, synchronized(v) { ArrayList(v.oggetti) })
        }

        val vive = ArrayList<PaginaViva>()
        for (k in 0 until n) {
            if (k < statiPrima.size) {
                vive += statiPrima[k].viva
            } else {
                val base = primaPage.pagina
                vive += PaginaViva(
                    Pagina(
                        Archive.nuovoId(),
                        base.sfondo,
                        larghezza = base.larghezza,
                        altezza = base.altezza,
                        layout = LayoutPagina.DOCUMENT,
                    ),
                ).apply { caricata = true }
            }
        }

        val statiDopo = ArrayList<StatoPaginaFlusso>()
        for (k in 0 until n) {
            val v = vive[k]
            val meta = v.pagina.copy(layout = LayoutPagina.DOCUMENT)
            val sansCorpo = ArrayList(
                (if (k < statiPrima.size) statiPrima[k].oggetti else emptyList())
                    .filterNot { it is Testo && DocumentMargins.estCorpo(it.id) },
            )
            val tr = tranches.getOrElse(k) { TrancheCorpo(TestoRicco.VUOTO, continua = false) }
            sansCorpo.add(0, DocumentMargins.corpo(meta, tr.contenuto, tr.continua))
            statiDopo += StatoPaginaFlusso(v, meta, sansCorpo)
        }

        registraFlusso(ModificaFlussoDocumento(catena.first, statiPrima, statiDopo))
    }

    private fun registraFlusso(m: ModificaFlussoDocumento) {
        applicaFlusso(m, inverso = false)
        indietro.addLast(m)
        if (indietro.size > 200) indietro.removeFirst()
        avanti.clear()
        alCambio?.invoke()
    }

    private fun applicaFlusso(m: ModificaFlussoDocumento, inverso: Boolean) {
        val da = if (inverso) m.dopo else m.prima
        val a = if (inverso) m.prima else m.dopo
        if (da.isNotEmpty()) {
            val start = m.indice.coerceIn(0, pagine.size)
            if (start + da.size <= pagine.size && (0 until da.size).all { pagine[start + it] === da[it].viva }) {
                repeat(da.size) { pagine.removeAt(start) }
            } else {
                da.forEach { pagine.remove(it.viva) }
            }
        }
        val insertAt = m.indice.coerceIn(0, pagine.size)
        for ((k, s) in a.withIndex()) {
            pagine.add(insertAt + k, s.viva)
            synchronized(s.viva) {
                s.viva.pagina = s.meta
                s.viva.sostituisciOggetti(s.oggetti)
            }
        }
        pagineSporche = true
    }

    private fun applicaPagine(m: ModificaPagine, inverso: Boolean) {
        val aggiungi = m.aggiunta != inverso
        if (aggiungi) pagine.add(m.indice.coerceIn(0, pagine.size), m.pagina) else pagine.remove(m.pagina)
        pagineSporche = true
    }

    val modificato: Boolean get() = pagineSporche || pagine.any { it.sporca || it.oggettiSporchi }

    /**
     * Il testo delle caselle di testo della nota aperta (anche non ancora salvate), come
     * [Archive.testoNota]: pagina per pagina, caselle separate da una riga vuota.
     */
    fun testoCaselle(): String = pagine
        .flatMap { p -> synchronized(p) { p.oggetti.filterIsInstance<Testo>() } }
        .map { it.contenuto.testoSemplice.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

    /** Salva su disco le pagine cambiate. Da chiamare fuori dal thread principale. */
    fun salva() {
        for (p in pagine.filter { it.sporca }) {
            val copia = synchronized(p) { p.sporca = false; ArrayList(p.tratti) }
            archivio.salvaTratti(info.id, p.pagina.id, copia)
        }
        for (p in pagine.filter { it.oggettiSporchi }) {
            val copia = synchronized(p) { p.oggettiSporchi = false; ArrayList(p.oggetti) }
            archivio.salvaOggetti(info.id, p.pagina.id, copia)
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
