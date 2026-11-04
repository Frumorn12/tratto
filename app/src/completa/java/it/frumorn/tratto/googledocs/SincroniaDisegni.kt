package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.NotaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Porta i disegni del foglio nel documento Google, davanti al testo (vedi [PianoDisegni]).
 *
 * 1. Si legge il documento e lo si impagina come su Tratto, per sapere dove sono i paragrafi.
 * 2. Sul paragrafo di ogni disegno nuovo si mette un intervallo con nome temporaneo, con gli indici
 *    della revisione letta: se nel frattempo qualcuno scrive, Google lo sposta con il testo.
 * 3. Lo script ([ScriptDocs]) ancora a quei paragrafi le immagini, davanti al testo, e nella
 *    stessa esecuzione toglie i disegni vecchi (cambiati o cancellati sul foglio) e gli intervalli:
 *    chi guarda il documento vede il disegno nuovo prendere il posto del vecchio in un colpo solo.
 * 4. Se ci sono solo disegni da togliere, o lo script e' quello vecchio che non sa toglierli, si
 *    tolgono con un batchUpdate.
 *
 * La prima volta si tolgono anche i blocchi messi nel documento dalla versione a blocchi (immagini
 * in linea e testi di Tratto), che ora sarebbero doppi.
 */
internal class SincroniaDisegni(
    private val docs: ApiDocs,
    private val script: ScriptDisegni,
    private val nota: NotaDocs,
    /** Impagina il documento come su Tratto (su Android con [PagineDoc]). */
    private val impagina: (ModelloDoc) -> PostiParagrafi,
    /** Cartella per i PNG (nella cache). */
    private val cartella: File,
) {
    /** Quanto e' durato ogni passaggio dell'ultimo [sincronizza], in millisecondi, per il registro. */
    val tempi = LinkedHashMap<String, Long>()
    private var segno = 0L

    private fun tempo(passaggio: String) {
        val ora = System.nanoTime()
        tempi[passaggio] = (tempi[passaggio] ?: 0L) + (ora - segno) / 1_000_000
        segno = ora
    }

    suspend fun sincronizza(link: CollegamentoDocs, progresso: suspend (CollegamentoDocs) -> Unit = {}): CollegamentoDocs {
        tempi.clear()
        segno = System.nanoTime()
        val (info, pagine) = withContext(Dispatchers.IO) { nota.leggi() }
        var l = link
        var json = docs.completo(l.documento)
        tempo("lettura")

        // Avanzi: intervalli di ancoraggio rimasti da uno script interrotto, blocchi della versione a blocchi.
        val pulizia = pulizia(l, json)
        if (pulizia.length() > 0) {
            docs.aggiornaSu(l.documento, pulizia, json.optString("revisionId"))
            json = docs.completo(l.documento)
        }
        if (l.vecchio || l.blocchi.isNotEmpty()) {
            l = l.copy(vecchio = false, blocchi = emptyList(), firme = emptyMap())
            progresso(l)
        }

        l = titolo(l, json, info)
        val modello = ModelloDoc.da(json)
        val lette = withContext(Dispatchers.IO) { pagine.map { nota.leggiPagina(it) } }
        tempo("pagine")
        val voluti = lette.flatMapIndexed { i, letta ->
            letta.blocchi.filterIsInstance<BloccoPagina.Disegno>().map { b ->
                // Chiave senza pagina: un disegno che il documento sposta su un'altra pagina resta lo stesso.
                DisegnoVoluto(b.chiave, letta.pagina.id, i, b.riquadro, BlocchiPagina.improntaRelativa(b))
            }
        }
        val piano = PianoDisegni(modello, impagina(modello), l.disegni, voluti)
        tempo("piano")
        if (piano.vuoto) {
            return l.copy(disegni = piano.tenuti)
        }

        // Intervalli temporanei sui paragrafi di ancoraggio dei disegni nuovi e, se c'e' da chiamare lo
        // script, anche su quelli dei disegni da togliere: lo script li trova li' anche se nel frattempo
        // qualcuno ha scritto.
        val nomi = piano.daAggiungere.map { nuovoNome() }
        val togli = if (nomi.isEmpty() || !scriptToglie) emptyList()
        else piano.daTogliere.map { o -> ScriptDocs.Togli(o, piano.paragrafoDi(o)?.let { nuovoNome() }) }
        if (nomi.isNotEmpty()) {
            val r = JSONArray()
            for ((k, x) in piano.daAggiungere.withIndex()) {
                val p = modello.paragrafi[x.second.paragrafo]
                r.put(RichiesteDocs.creaIntervallo(nomi[k], p.inizio, p.inizio + 1))
            }
            for (t in togli) {
                val nome = t.ancora ?: continue
                val p = modello.paragrafi[piano.paragrafoDi(t.oggetto) ?: continue]
                r.put(RichiesteDocs.creaIntervallo(nome, p.inizio, p.inizio + 1))
            }
            docs.aggiornaSu(l.documento, r, modello.revisione)
            tempo("ancore")
        }

        // I PNG dei disegni e lo script.
        val nuovi = ArrayList<DisegnoSalvato>(piano.tenuti)
        var tolti: Set<String> = emptySet()
        if (piano.daAggiungere.isNotEmpty()) {
            val perPagina = lette.associateBy { it.pagina.id }
            val disegni = piano.daAggiungere.mapIndexed { k, (v, a) ->
                val letta = perPagina.getValue(v.pagina)
                val blocco = letta.blocchi.first { it is BloccoPagina.Disegno && it.chiave == v.chiave }
                val file = File(cartella, "${nota.id}-disegno-$k.png")
                try {
                    nota.disegna(info, letta, blocco, file)
                    ScriptDocs.Disegno(nomi[k], withContext(Dispatchers.IO) { file.readBytes() }, a.larghezza, a.altezza, a.sinistra, a.alto)
                } finally {
                    file.delete()
                }
            }
            tempo("png")
            val esito = script.aggiungiDisegni(l.documento, disegni, togli)
            val ids = esito.ids
            tolti = esito.tolti.orEmpty()
            // Lo script vecchio lascia gli intervalli dei disegni da togliere: li toglie la pulizia della
            // prossima volta, e finche' l'app resta aperta non si mettono piu'.
            scriptToglie = esito.tolti != null
            tempo("script")
            for ((k, x) in piano.daAggiungere.withIndex()) {
                val id = ids.getOrNull(k) ?: continue
                nuovi += DisegnoSalvato(x.first.chiave, x.first.pagina, id, x.first.impronta)
            }
        }
        l = l.copy(disegni = nuovi)
        progresso(l)

        // I disegni vecchi che lo script non ha tolto: se qualcuno li ha gia' tolti, si rilegge e si
        // toglie quello che resta.
        val daTogliere = piano.daTogliere.filter { it !in tolti }
        if (daTogliere.isNotEmpty()) {
            try {
                docs.aggiorna(l.documento, JSONArray().apply { daTogliere.forEach { put(RichiesteDocs.togliPosizionato(it)) } })
            } catch (e: ErroreDrive) {
                if (e.codice != 400) throw e
                val ancora = docs.completo(l.documento).optJSONObject("positionedObjects")
                val resti = daTogliere.filter { ancora?.has(it) == true }
                if (resti.isNotEmpty()) docs.aggiorna(l.documento, JSONArray().apply { resti.forEach { put(RichiesteDocs.togliPosizionato(it)) } })
            }
            tempo("tolti")
        }
        return l
    }

    /** Le richieste per togliere gli avanzi: intervalli di ancoraggio e blocchi della versione a blocchi. */
    private fun pulizia(l: CollegamentoDocs, json: JSONObject): JSONArray {
        val d = DocLetto.da(json)
        val out = JSONArray()
        for (r in d.intervalli) if (r.nome.startsWith(PREFISSO_ANCORA)) out.put(RichiesteDocs.togliIntervallo(r.id))
        if (l.blocchi.isNotEmpty()) {
            // Il piano dei blocchi senza nessun blocco voluto toglie esattamente quelli di Tratto.
            val r = PianoBlocchi(d, l.blocchi, emptyList()).richieste(emptyMap()).json
            for (i in 0 until r.length()) {
                val x = r.getJSONObject(i)
                // Gli intervalli li ha gia' tolti il ciclo sopra solo se sono di ancoraggio: questi sono tratto/...
                out.put(x)
            }
        } else if (l.vecchio) {
            // Prima versione: una immagine larga 440 punti per pagina, da sola nel suo paragrafo.
            val immagini = json.optJSONObject("inlineObjects")
            for (e in d.elementi.asReversed()) {
                val id = e.soloImmagine ?: continue
                val w = immagini?.optJSONObject(id)?.optJSONObject("inlineObjectProperties")?.optJSONObject("embeddedObject")
                    ?.optJSONObject("size")?.optJSONObject("width")?.optDouble("magnitude") ?: continue
                if (kotlin.math.abs(w - 440.0) < 1.0 && e.fine < d.fine) out.put(RichiesteDocs.cancella(e.inizio, e.fine))
                else if (kotlin.math.abs(w - 440.0) < 1.0) out.put(RichiesteDocs.cancella(e.oggetti[0].second, e.oggetti[0].second + 1))
            }
        }
        return out
    }

    private fun nuovoNome() = "$PREFISSO_ANCORA${UUID.randomUUID()}"

    /**
     * Il nome della nota va al documento quando cambia su Tratto. Se e' cambiato solo in Google Docs
     * (Tratto ha ancora il nome che aveva mandato), resta quello scelto in Docs.
     */
    private suspend fun titolo(l: CollegamentoDocs, json: JSONObject, info: NotaInfo): CollegamentoDocs {
        val nome = SincroniaDocs.nomeDocumento(info)
        if (json.optString("title") == nome) return if (l.titolo == nome) l else l.copy(titolo = nome)
        if (l.titolo == nome) return l
        docs.rinomina(l.documento, nome)
        tempo("nome")
        return l.copy(titolo = nome)
    }

    companion object {
        /** Lo script su Google sa togliere i disegni vecchi (falso dopo una risposta dello script vecchio). */
        @Volatile var scriptToglie = true

        /** Nome degli intervalli temporanei su cui lo script ancora i disegni. */
        const val PREFISSO_ANCORA = "tratto-ancora-"
    }
}
