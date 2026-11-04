package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.ErroreDrive
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
 * 3. Lo script ([ScriptDocs]) ancora a quei paragrafi le immagini, davanti al testo, e toglie gli
 *    intervalli.
 * 4. Si tolgono i disegni vecchi (cambiati o cancellati sul foglio).
 *
 * La prima volta si tolgono anche i blocchi messi nel documento dalla versione a blocchi (immagini
 * in linea e testi di Tratto), che ora sarebbero doppi.
 */
internal class SincroniaDisegni(
    private val docs: DocsRest,
    private val script: ScriptDocs,
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

        // Intervalli temporanei sui paragrafi di ancoraggio.
        val nomi = piano.daAggiungere.map { "$PREFISSO_ANCORA${UUID.randomUUID()}" }
        if (nomi.isNotEmpty()) {
            val r = JSONArray()
            for ((k, x) in piano.daAggiungere.withIndex()) {
                val p = modello.paragrafi[x.second.paragrafo]
                r.put(RichiesteDocs.creaIntervallo(nomi[k], p.inizio, p.inizio + 1))
            }
            docs.aggiornaSu(l.documento, r, modello.revisione)
            tempo("ancore")
        }

        // I PNG dei disegni e lo script.
        val nuovi = ArrayList<DisegnoSalvato>(piano.tenuti)
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
            val ids = script.aggiungiDisegni(l.documento, disegni)
            tempo("script")
            for ((k, x) in piano.daAggiungere.withIndex()) {
                val id = ids.getOrNull(k) ?: continue
                nuovi += DisegnoSalvato(x.first.chiave, x.first.pagina, id, x.first.impronta)
            }
        }
        l = l.copy(disegni = nuovi)
        progresso(l)

        // I disegni vecchi: se qualcuno li ha gia' tolti, si rilegge e si toglie quello che resta.
        if (piano.daTogliere.isNotEmpty()) {
            try {
                docs.aggiorna(l.documento, JSONArray().apply { piano.daTogliere.forEach { put(RichiesteDocs.togliPosizionato(it)) } })
            } catch (e: ErroreDrive) {
                if (e.codice != 400) throw e
                val ancora = docs.completo(l.documento).optJSONObject("positionedObjects")
                val resti = piano.daTogliere.filter { ancora?.has(it) == true }
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

    companion object {
        /** Nome degli intervalli temporanei su cui lo script ancora i disegni. */
        const val PREFISSO_ANCORA = "tratto-ancora-"
    }
}
