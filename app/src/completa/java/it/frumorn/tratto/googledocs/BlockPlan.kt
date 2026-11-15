package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cosa cambiare nel documento Google per passare dai blocchi che ci sono ([salvati], ritrovati in
 * [doc]) a quelli [voluti], toccando solo i blocchi di Tratto: il resto del documento (testo e
 * immagini aggiunti a mano in Google Docs) resta com'e' e dove e'.
 *
 * Come si ritrovano i blocchi:
 * - una figura dall'id della sua immagine. Se qualcuno ha scritto nello stesso paragrafo, il paragrafo
 *   non e' piu' di Tratto: si cambia o si toglie solo l'immagine;
 * - un testo dal suo intervallo con nome, che deve cominciare all'inizio di un paragrafo. Sono di
 *   Tratto solo i paragrafi che aveva scritto (quanti erano), anche se l'intervallo nel frattempo si e'
 *   allargato; se il testo e' stato cambiato a mano si riscrive solo quando cambia sul tablet.
 * - un blocco che non si ritrova (cancellato a mano) si rimette.
 *
 * I blocchi ritrovati nello stesso ordine della nota restano dove sono (sottosequenza comune piu'
 * lunga); gli altri si tolgono e si rimettono subito dopo il blocco di Tratto che li precede.
 *
 * Le richieste: prima si tolgono gli intervalli di Tratto, poi i cambi del contenuto dal fondo verso
 * l'inizio (ognuno sposta solo indici gia' sistemati), poi con gli indici finali gli intervalli nuovi,
 * gli stili e, per ultimi e dal fondo, gli elenchi (che tolgono i tab davanti ai paragrafi).
 *
 * [identiche] sono figure con l'impronta cambiata ma il disegno uguale: non si ricaricano.
 * Con [vecchio] il documento e' della prima versione (tutto di Tratto) e si svuota.
 */
internal class BlockPlan(
    private val doc: DocLetto,
    salvati: List<BloccoSalvato>,
    private val voluti: List<BloccoVoluto>,
    vecchio: Boolean = false,
    private val identiche: Set<String> = emptySet(),
) {
    /** Un blocco salvato ritrovato nel documento: elementi da [primo] per [quanti] (0: immagine in un paragrafo non di Tratto, in [carattere]). */
    private class Posto(val chiave: String, val salvato: BloccoSalvato?, val primo: Int, val quanti: Int, val carattere: Int, val intatto: Boolean) {
        val ultimo get() = if (quanti == 0) primo else primo + quanti - 1
        val intero get() = quanti > 0
    }

    private val el = doc.elementi
    private val posti: List<Posto>

    /** Indice in voluti -> blocco ritrovato che resta al suo posto. */
    private val tenuti = HashMap<Int, Posto>()
    private val cancellati = ArrayList<Posto>()

    /** Voluti da scrivere (nuovi o cambiati) e figure rimaste da ristilare perche' spostate. */
    private val scritti = HashSet<Int>()
    private val ristilare = HashSet<Int>()

    /** Intervalli di Tratto da rifare: spostati, allargati, doppi, rimasti senza blocco. */
    private val intervalliStorti: Boolean

    init {
        posti = if (vecchio) {
            if (el.isEmpty()) emptyList() else listOf(Posto(VECCHIO, null, 0, el.size, el[0].inizio, true))
        } else {
            senzaSovrapposizioni(salvati.mapNotNull { trova(it) })
        }
        for ((i, j) in sottosequenza(posti.map { it.chiave }, voluti.map { it.chiave })) tenuti[j] = posti[i]
        val tenutiSet = tenuti.values.toSet()
        posti.filterTo(cancellati) { it !in tenutiSet }

        for ((j, v) in voluti.withIndex()) {
            val p = tenuti[j]
            if (p == null) {
                scritti += j
                continue
            }
            val s = p.salvato!!
            when (v) {
                is BloccoVoluto.Figura -> when {
                    v.impronta != s.impronta && v.chiave !in identiche -> scritti += j
                    v.aspetto.codice != s.aspetto && p.intero -> ristilare += j
                    // Misure diverse con lo stesso disegno: si rimette l'immagine grande giusta.
                    (v.larghezzaPt != s.larghezzaPt || v.altezzaPt != s.altezzaPt) && v.chiave !in identiche -> scritti += j
                }
                is BloccoVoluto.Scritto -> if (v.impronta != s.impronta || v.aspetto.codice != s.aspetto) scritti += j
            }
        }

        // Gli intervalli di Tratto nel documento devono essere esattamente quelli dei testi ritrovati.
        val attesi = HashMap<String, Pair<Int, Int>>()
        for (p in posti) if (p.salvato?.figura == false) attesi[p.salvato.intervallo ?: ""] = el[p.primo].inizio to el[p.ultimo].fine
        val nostri = doc.intervalli.filter { it.nome.startsWith(DocsContent.PREFISSO_INTERVALLI) }
        intervalliStorti = nostri.size != attesi.size || nostri.any { attesi[it.id] != (it.inizio to it.fine) }
    }

    /** Chiavi dei blocchi ritrovati che restano al loro posto (gli altri si inseriscono). */
    val tenute: Set<String> get() = tenuti.keys.map { voluti[it].chiave }.toSet()

    /**
     * Blocchi tolti che lasciano il loro ultimo a capo (e quindi un paragrafo vuoto): quello in fondo
     * al corpo e quelli subito prima di una tabella o di altro che non e' un paragrafo.
     */
    private val conResto: Set<Posto> by lazy {
        cancellati.filter { p -> p.intero && (p.ultimo == el.lastIndex || el.getOrNull(p.ultimo + 1)?.paragrafo == false) }.toSet()
    }

    /** Figure da disegnare e caricare (nuove o cambiate). */
    val daDisegnare: List<BloccoVoluto.Figura> get() = scritti.sorted().mapNotNull { voluti[it] as? BloccoVoluto.Figura }

    /** Testi da scrivere di cui manca il contenuto: le loro pagine vanno lette. */
    val senzaContenuto: List<BloccoVoluto.Scritto>
        get() = scritti.sorted().mapNotNull { (voluti[it] as? BloccoVoluto.Scritto)?.takeIf { s -> s.paragrafi == null } }

    /** Vero se il documento e' gia' giusto. */
    val vuoto: Boolean get() = scritti.isEmpty() && ristilare.isEmpty() && cancellati.isEmpty() && !intervalliStorti

    // ---------------------------------------------------------------- ritrovare i blocchi

    private fun trova(s: BloccoSalvato): Posto? {
        if (s.figura) {
            val id = s.oggetto ?: return null
            for ((k, e) in el.withIndex()) {
                val o = e.oggetti.firstOrNull { it.first == id } ?: continue
                return Posto(s.chiave, s, k, if (e.soloImmagine == id) 1 else 0, o.second, true)
            }
            return null
        }
        val r = doc.intervalli.firstOrNull { it.id == s.intervallo }
            ?: doc.intervalli.firstOrNull { it.nome == DocsContent.nomeIntervallo(s.chiave) }
            ?: return null
        // Un intervallo che non comincia all'inizio di un paragrafo: qualcuno ha unito il testo di
        // Tratto a un paragrafo suo. Quel testo ormai e' suo; il blocco si rimette.
        val k = el.indexOfFirst { it.paragrafo && it.inizio == r.inizio }
        if (k < 0) return null
        var m = 0
        while (k + m < el.size && el[k + m].paragrafo && el[k + m].inizio < r.fine) m++
        if (m == 0) return null
        val n = s.paragrafi.size.coerceAtLeast(1)
        if (m <= n) return Posto(s.chiave, s, k, m, el[k].inizio, m == s.paragrafi.size && (0 until m).all { uguale(el[k + it], s.paragrafi[it]) })
        // L'intervallo si e' allargato: qualcuno ha scritto proprio prima o dopo il testo di Tratto,
        // e Google ha messo il testo nuovo dentro. Il testo di Tratto sono gli n paragrafi che gli
        // somigliano di piu' (a pari merito i primi).
        var migliore = 0
        var uguali = -1
        for (o in 0..m - n) {
            val c = (0 until n).count { uguale(el[k + o + it], s.paragrafi[it]) }
            if (c > uguali) {
                uguali = c
                migliore = o
            }
        }
        return Posto(s.chiave, s, k + migliore, n, el[k + migliore].inizio, uguali == n)
    }

    /** Il paragrafo [e] e' ancora quello che Tratto aveva scritto (con [impronta]). */
    private fun uguale(e: ElementoDoc, impronta: String) =
        e.oggetti.isEmpty() && !e.altro && BloccoSalvato.impronta(e.testo) == impronta

    /** Un elemento appartiene a un blocco solo: se due si contendono lo stesso, vince il primo. */
    private fun senzaSovrapposizioni(trovati: List<Posto>): List<Posto> {
        val presi = HashSet<Int>()
        val caratteri = HashSet<Int>()
        val out = ArrayList<Posto>()
        for (p in trovati.sortedWith(compareBy({ it.primo }, { it.carattere }))) {
            if (p.intero) {
                if ((p.primo..p.ultimo).any { it in presi }) continue
                (p.primo..p.ultimo).forEach { presi += it }
            } else {
                if (p.primo in presi || !caratteri.add(p.carattere)) continue
            }
            out += p
        }
        // Un'immagine rimasta dentro un paragrafo che poi un altro blocco ha preso: non vale.
        return out.filter { it.intero || it.primo !in presi }
    }

    // ---------------------------------------------------------------- richieste

    /** Le richieste e, per leggere la risposta, la chiave del blocco di ogni immagine inserita e di ogni intervallo creato. */
    class Richieste(val json: JSONArray, val immagini: Map<Int, String>, val intervalli: Map<Int, String>)

    private class Op(val pos: Int, val prio: Int, val richieste: List<Pair<JSONObject, String?>>)

    /** Le richieste di batchUpdate. [uri] da' l'indirizzo pubblico del PNG di ogni figura di [daDisegnare]. */
    fun richieste(uri: Map<String, String>): Richieste {
        val fineCorpo = doc.fine
        val ops = ArrayList<Op>()

        // Blocchi tolti.
        for (p in cancellati) {
            if (!p.intero) {
                ops += Op(p.carattere, PRIO_CAMBIO, listOf(DocsRequests.cancella(p.carattere, p.carattere + 1) to null))
                continue
            }
            val a = el[p.primo].inizio
            var b = el[p.ultimo].fine
            // L'ultimo a capo del corpo non si cancella, e nemmeno quello prima di una tabella: resta
            // un paragrafo vuoto, che torna normale (vedi sotto).
            if (p in conResto) b -= 1
            if (a < b) ops += Op(a, PRIO_CAMBIO, listOf(DocsRequests.cancella(a, b) to null))
        }

        // Blocchi rimasti al loro posto ma cambiati.
        for ((j, p) in tenuti) {
            if (j !in scritti) continue
            when (val v = voluti[j]) {
                is BloccoVoluto.Figura -> ops += Op(
                    p.carattere, PRIO_CAMBIO,
                    listOf(DocsRequests.cancella(p.carattere, p.carattere + 1) to null, immagine(v, p.carattere, uri) to v.chiave),
                )
                is BloccoVoluto.Scritto -> {
                    val a = el[p.primo].inizio
                    val b = el[p.ultimo].fine - 1
                    val r = ArrayList<Pair<JSONObject, String?>>()
                    if (a < b) r += DocsRequests.cancella(a, b) to null
                    val t = testo(v)
                    if (t.isNotEmpty()) r += DocsRequests.inserisciTesto(a, t) to null
                    if (r.isNotEmpty()) ops += Op(a, PRIO_CAMBIO, r)
                }
            }
        }

        // Blocchi nuovi (o spostati), subito dopo il blocco di Tratto che li precede, o all'inizio.
        // Gruppi che vanno nello stesso punto (immagini di Tratto nello stesso paragrafo) si uniscono.
        val perPunto = LinkedHashMap<Int, MutableList<Int>>()
        for ((ancora, js) in gruppi()) {
            val punto = if (ancora < 0) -1 else el[tenuti.getValue(ancora).ultimo].fine
            perPunto.getOrPut(punto) { ArrayList() } += js
        }
        for ((punto, js) in perPunto) {
            val r = ArrayList<Pair<JSONObject, String?>>()
            if (punto < 0) {
                for (j in js.asReversed()) when (val v = voluti[j]) {
                    is BloccoVoluto.Scritto -> r += DocsRequests.inserisciTesto(1, testo(v) + "\n") to null
                    is BloccoVoluto.Figura -> {
                        r += DocsRequests.inserisciTesto(1, "\n") to null
                        r += immagine(v, 1, uri) to v.chiave
                    }
                }
                ops += Op(1, PRIO_INIZIO, r)
            } else {
                // Si aggiunge "a capo + blocco" prima dell'a capo che chiude il paragrafo dell'ancora:
                // funziona anche se dopo c'e' una tabella o la fine del corpo.
                val e = punto
                js.sort()
                for (j in js.asReversed()) when (val v = voluti[j]) {
                    is BloccoVoluto.Scritto -> r += DocsRequests.inserisciTesto(e - 1, "\n" + testo(v)) to null
                    is BloccoVoluto.Figura -> {
                        r += DocsRequests.inserisciTesto(e - 1, "\n") to null
                        r += immagine(v, e, uri) to v.chiave
                    }
                }
                ops += Op(e - 1, PRIO_DOPO, r)
            }
        }

        val out = ArrayList<Pair<JSONObject, String?>>()
        val intervalli = HashMap<Int, String>()
        // Gli intervalli di Tratto si tolgono tutti e si rifanno alla fine, esatti.
        for (r in doc.intervalli) if (r.nome.startsWith(DocsContent.PREFISSO_INTERVALLI)) out += DocsRequests.togliIntervallo(r.id) to null
        for (op in ops.sortedWith(compareByDescending<Op> { it.pos }.thenByDescending { it.prio })) out += op.richieste

        // Indici finali.
        val finale = impaginaFinale()
        for ((j, v) in voluti.withIndex()) {
            if (v !is BloccoVoluto.Scritto) continue
            val (a, b) = finale.estensione(j)
            intervalli[out.size] = v.chiave
            out += DocsRequests.creaIntervallo(DocsContent.nomeIntervallo(v.chiave), a, b) to null
        }
        val elenchi = ArrayList<Triple<Int, Int, String>>()
        for ((j, v) in voluti.withIndex()) {
            val (a, _) = finale.estensione(j)
            when (v) {
                is BloccoVoluto.Figura ->
                    if ((j in scritti && tenuti[j]?.intero != false) || j in ristilare) {
                        out += DocsRequests.stileParagrafo(a, a + 2, DocsContent.stileFigura(v.aspetto), DocsContent.CAMPI_PARAGRAFO) to null
                    }
                is BloccoVoluto.Scritto -> if (j in scritti) stiliTesto(v, a, out, elenchi)
            }
        }
        for ((k, pos) in finale.daRipristinare) {
            val s = el[k].stile ?: continue
            out += DocsRequests.stileParagrafo(pos, pos + 1, ripristino(s), CAMPI_RIPRISTINO) to null
        }
        for (r in finale.resti) out += DocsRequests.stileParagrafo(r, r + 1, DocsContent.stileNormale(), DocsContent.CAMPI_PARAGRAFO) to null
        for ((a, b, modello) in elenchi.sortedByDescending { it.first }) out += DocsRequests.elenco(a, b, modello) to null

        val json = JSONArray()
        val immagini = HashMap<Int, String>()
        for ((k, r) in out.withIndex()) {
            json.put(r.first)
            r.second?.let { immagini[k] = it }
        }
        return Richieste(json, immagini, intervalli)
    }

    /** Stili dei paragrafi e del testo di un blocco di testo che comincia in [a]; gli elenchi vanno in [elenchi]. */
    private fun stiliTesto(v: BloccoVoluto.Scritto, a: Int, out: MutableList<Pair<JSONObject, String?>>, elenchi: MutableList<Triple<Int, Int, String>>) {
        val par = v.paragrafi!!
        var s = a
        var elenco: Triple<Int, Int, String>? = null
        for ((i, p) in par.withIndex()) {
            val l = p.daInserire.length
            out += DocsRequests.stileParagrafo(s, s + l + 1, DocsContent.stileScritto(p, v.aspetto, i == 0), DocsContent.CAMPI_PARAGRAFO) to null
            val base = s + (l - p.testo.length)
            for (x in p.pezzi) {
                if (x.fine > x.inizio) out += DocsRequests.stileTesto(base + x.inizio, base + x.fine, DocsContent.stilePezzo(x), DocsContent.CAMPI_TESTO) to null
            }
            // Paragrafi vicini dello stesso elenco: un elenco solo, cosi' la numerazione continua.
            val e = elenco
            elenco = when {
                p.elenco == null -> null.also { if (e != null) elenchi += e }
                e != null && e.third == p.elenco -> e.copy(second = s + l + 1)
                else -> Triple(s, s + l + 1, p.elenco).also { if (e != null) elenchi += e }
            }
            s += l + 1
        }
        elenco?.let { elenchi += it }
    }

    private fun testo(v: BloccoVoluto.Scritto) = v.paragrafi!!.joinToString("\n") { it.daInserire }

    private fun immagine(v: BloccoVoluto.Figura, indice: Int, uri: Map<String, String>) =
        DocsRequests.inserisciImmagine(indice, uri[v.chiave] ?: error("Manca l'immagine del blocco ${v.chiave}"), v.larghezzaPt, v.altezzaPt)

    /** I voluti da inserire, raggruppati per ancora: l'indice del blocco rimasto che li precede (-1: l'inizio del documento). */
    private fun gruppi(): Map<Int, List<Int>> {
        val out = LinkedHashMap<Int, MutableList<Int>>()
        var ancora = -1
        for (j in voluti.indices) {
            if (j in tenuti) ancora = j else out.getOrPut(ancora) { ArrayList() } += j
        }
        return out
    }

    // ---------------------------------------------------------------- impaginazione finale

    private class Finale(
        val blocchi: Map<Int, Pair<Int, Int>>,
        val daRipristinare: List<Pair<Int, Int>>,
        val resti: List<Int>,
    ) {
        fun estensione(j: Int) = blocchi.getValue(j)
    }

    /**
     * Il documento dopo i cambi del contenuto: dove comincia e finisce ogni blocco voluto, dove sono i
     * paragrafi che seguono un blocco tolto (a cui ridare il loro stile: una cancellazione puo' passar
     * loro quello del paragrafo tolto) e il paragrafo vuoto rimasto in fondo.
     */
    private fun impaginaFinale(): Finale {
        val blocchi = HashMap<Int, Pair<Int, Int>>()
        val ripristinare = ArrayList<Pair<Int, Int>>()
        val resti = ArrayList<Int>()
        var pos = 1
        val gruppi = gruppi()
        val postoVoluto = tenuti.entries.associate { (j, p) -> p to j }
        val intero = HashMap<Int, Posto>()
        for (p in posti) if (p.intero) for (k in p.primo..p.ultimo) intero[k] = p
        val caratteriTolti = cancellati.filter { !it.intero }.groupingBy { it.primo }.eachCount()
        val condiviseTenute = tenuti.entries.filter { !it.value.intero }.groupBy({ it.value.primo }, { it.key })
        val cancellatiSet = cancellati.toSet()
        var dopoCancellato = false

        fun aggiungiGruppo(ancora: Int) {
            for (j in gruppi[ancora].orEmpty()) {
                val l = lunghezza(voluti[j])
                blocchi[j] = pos to pos + l
                pos += l
            }
        }

        aggiungiGruppo(-1)
        var k = 0
        while (k < el.size) {
            val p = intero[k]
            if (p != null) {
                if (p in cancellatiSet) {
                    if (p in conResto) {
                        resti += pos
                        pos += 1
                    }
                    dopoCancellato = true
                    k = p.ultimo + 1
                    continue
                }
                val j = postoVoluto.getValue(p)
                val inizio = pos
                if (j in scritti && voluti[j] is BloccoVoluto.Scritto) {
                    pos += lunghezza(voluti[j])
                } else {
                    if (dopoCancellato && j !in scritti && j !in ristilare) ripristinare += p.primo to pos
                    for (x in p.primo..p.ultimo) pos += el[x].fine - el[x].inizio
                }
                blocchi[j] = inizio to pos
                dopoCancellato = false
                aggiungiGruppo(j)
                k = p.ultimo + 1
                continue
            }
            if (dopoCancellato && el[k].paragrafo) ripristinare += k to pos
            dopoCancellato = false
            val inizio = pos
            pos += el[k].fine - el[k].inizio - (caratteriTolti[k] ?: 0)
            for (j in condiviseTenute[k].orEmpty().sorted()) {
                blocchi[j] = inizio to pos
                aggiungiGruppo(j)
            }
            k++
        }
        return Finale(blocchi, ripristinare, resti)
    }

    /** Lunghezza nel documento di un blocco scritto da Tratto (con i tab degli elenchi, che spariscono per ultimi). */
    private fun lunghezza(v: BloccoVoluto): Int = when (v) {
        is BloccoVoluto.Figura -> 2
        is BloccoVoluto.Scritto -> v.paragrafi!!.sumOf { it.daInserire.length + 1 }
    }

    /** Lo stile letto di un paragrafo, da rimettere: i campi che non c'erano tornano quelli ereditati. */
    private fun ripristino(s: JSONObject): JSONObject {
        val j = JSONObject()
        for (c in CAMPI_RIPRISTINO.split(',')) if (s.has(c)) j.put(c, s.get(c))
        return j
    }

    // ---------------------------------------------------------------- stato dopo l'aggiornamento

    /**
     * I blocchi salvati dopo l'aggiornamento, dalla [risposta] di batchUpdate (null se non c'e' stato
     * perche' il piano era [vuoto]). [png] e' lo SHA-256 del PNG di ogni figura caricata.
     */
    fun stato(richieste: Richieste?, risposta: JSONObject?, png: Map<String, String>): List<BloccoSalvato> {
        val repliche = risposta?.optJSONArray("replies")
        val oggetti = HashMap<String, String>()
        val intervalli = HashMap<String, String>()
        if (richieste != null && repliche != null) {
            for ((k, chiave) in richieste.immagini) {
                repliche.optJSONObject(k)?.optJSONObject("insertInlineImage")?.optString("objectId")?.takeIf { it.isNotEmpty() }?.let { oggetti[chiave] = it }
            }
            for ((k, chiave) in richieste.intervalli) {
                repliche.optJSONObject(k)?.optJSONObject("createNamedRange")?.optString("namedRangeId")?.takeIf { it.isNotEmpty() }?.let { intervalli[chiave] = it }
            }
        }
        return voluti.mapIndexed { j, v ->
            val s = tenuti[j]?.salvato
            val scritto = j in scritti && richieste != null
            when (v) {
                is BloccoVoluto.Figura -> BloccoSalvato(
                    chiave = v.chiave, figura = true, pagina = v.pagina, impronta = v.impronta, aspetto = v.aspetto.codice,
                    oggetto = if (scritto) oggetti[v.chiave] else s?.oggetto,
                    png = if (scritto) png[v.chiave] else s?.png,
                    larghezzaPt = v.larghezzaPt, altezzaPt = v.altezzaPt,
                )
                is BloccoVoluto.Scritto -> BloccoSalvato(
                    chiave = v.chiave, figura = false, pagina = v.pagina,
                    impronta = if (scritto || s == null) v.impronta else s.impronta,
                    aspetto = if (scritto || s == null) v.aspetto.codice else s.aspetto,
                    intervallo = intervalli[v.chiave] ?: s?.intervallo,
                    paragrafi = if (scritto) v.paragrafi!!.map { BloccoSalvato.impronta(it.testo) } else s?.paragrafi.orEmpty(),
                )
            }
        }
    }

    companion object {
        private const val VECCHIO = "\u0000vecchio"

        // A pari posizione: prima cio' che si aggiunge dopo un blocco (sta prima del suo a capo), poi
        // cancellazioni e cambi, per ultimo cio' che va all'inizio del documento (davanti a tutto).
        private const val PRIO_DOPO = 2
        private const val PRIO_CAMBIO = 1
        private const val PRIO_INIZIO = 0

        private const val CAMPI_RIPRISTINO = "namedStyleType,alignment,indentStart,indentFirstLine,spaceAbove"

        /** Sottosequenza comune piu' lunga, come coppie di indici in ordine crescente. */
        fun sottosequenza(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
            val n = a.size
            val m = b.size
            // l[i][j] = lunghezza della sottosequenza comune piu' lunga di a[i..] e b[j..]
            val l = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                l[i][j] = if (a[i] == b[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
            }
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> { out += i to j; i++; j++ }
                    l[i + 1][j] >= l[i][j + 1] -> i++
                    else -> j++
                }
            }
            return out
        }
    }
}
