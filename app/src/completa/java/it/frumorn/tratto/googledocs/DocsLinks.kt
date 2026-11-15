package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Un blocco di Tratto gia' nel documento Google (vedi [BloccoVoluto]). Si ritrova nel documento cosi':
 * una figura dall'id della sua immagine ([oggetto]), un testo dal suo intervallo con nome ([intervallo],
 * che si chiama [nomeIntervallo]) e dall'impronta dei suoi paragrafi ([paragrafi]).
 */
internal data class BloccoSalvato(
    val chiave: String,
    val figura: Boolean,
    /** Pagina di Tratto da cui viene (null per il titolo della nota). */
    val pagina: String?,
    /** Impronta dei dati da cui e' fatto: finche' non cambia, il blocco nel documento e' giusto. */
    val impronta: String,
    /** Posizione nel documento (vedi [Aspetto.codice]). */
    val aspetto: String,
    /** Figura: id dell'immagine nel documento (inlineObjectId). */
    val oggetto: String? = null,
    /** Figura: SHA-256 del PNG caricato; se il disegno nuovo e' identico non si ricarica. */
    val png: String? = null,
    /** Testo: id dell'intervallo con nome che lo copre. */
    val intervallo: String? = null,
    /** Testo: impronta del testo di ogni paragrafo come l'ha scritto Tratto (vedi [impronta]). */
    val paragrafi: List<String> = emptyList(),
    /** Figura: misure nel documento, in punti. */
    val larghezzaPt: Double = 0.0,
    val altezzaPt: Double = 0.0,
) {
    companion object {
        /** Impronta breve del testo di un paragrafo, per riconoscere se nel documento e' stato cambiato. */
        fun impronta(testo: String): String =
            MessageDigest.getInstance("SHA-256").digest(testo.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Una nota collegata: il suo documento Google e i blocchi che Tratto ci ha messo, in ordine.
 * [firme] ricorda per ogni pagina la firma dei dati quando i suoi blocchi sono stati messi nel
 * documento: finche' non cambia, la pagina non si rilegge. [vecchio] e' un documento fatto dalla
 * prima versione (una immagine per pagina): al primo aggiornamento si svuota e si rifa' a blocchi.
 */
internal data class CollegamentoDocs(
    val documento: String,
    val blocchi: List<BloccoSalvato> = emptyList(),
    val firme: Map<String, String> = emptyMap(),
    val vecchio: Boolean = false,
    /** I disegni di Tratto davanti al testo del documento. */
    val disegni: List<DisegnoSalvato> = emptyList(),
    /** Il nome che Tratto ha dato al documento l'ultima volta (null se non lo sa ancora). */
    val titolo: String? = null,
)

/**
 * Le note collegate a Google Docs, salvate in files/google-docs/collegamenti.json:
 *
 *   {"versione": 2, "cartella": "<id di Tratto su Drive>", "immagini": "<id della cartella temporanea>",
 *    "note": {"<notaId>": {"documento": "<id>", "firme": {"<paginaId>": ".."},
 *                          "blocchi": [{"chiave": .., "figura": true, "pagina": .., "impronta": .., "aspetto": ..,
 *                                       "oggetto": .., "png": .., "larghezza": .., "altezza": ..},
 *                                      {"chiave": .., "figura": false, .., "intervallo": .., "paragrafi": [..]}]}}}
 *
 * Il file sta fuori da files/tratto apposta: e' lo stato della sincronizzazione di questo tablet,
 * come files/backup/drive.json, e non deve finire nei backup ne' essere sostituito da un ripristino.
 * Un file della versione 1 (una immagine per pagina) si legge con le note segnate [CollegamentoDocs.vecchio].
 */
internal data class DocsLinks(
    val note: Map<String, CollegamentoDocs> = emptyMap(),
    /** Cartella "Tratto" su Drive, la stessa del backup. */
    val cartella: String? = null,
    /** Sottocartella delle immagini temporanee. */
    val immagini: String? = null,
) {
    fun json(): JSONObject {
        val n = JSONObject()
        for ((id, c) in note) {
            val blocchi = JSONArray()
            for (b in c.blocchi) {
                val j = JSONObject().put("chiave", b.chiave).put("figura", b.figura).put("impronta", b.impronta).put("aspetto", b.aspetto)
                b.pagina?.let { j.put("pagina", it) }
                if (b.figura) {
                    j.put("oggetto", b.oggetto ?: "").put("png", b.png ?: "").put("larghezza", b.larghezzaPt).put("altezza", b.altezzaPt)
                } else {
                    j.put("intervallo", b.intervallo ?: "").put("paragrafi", JSONArray(b.paragrafi))
                }
                blocchi.put(j)
            }
            val disegni = JSONArray()
            for (d in c.disegni) disegni.put(JSONObject().put("chiave", d.chiave).put("pagina", d.pagina).put("oggetto", d.oggetto).put("impronta", d.impronta))
            val c2 = JSONObject().put("documento", c.documento).put("blocchi", blocchi).put("firme", JSONObject(c.firme as Map<*, *>)).put("disegni", disegni)
            if (c.vecchio) c2.put("vecchio", true)
            c.titolo?.let { c2.put("titolo", it) }
            n.put(id, c2)
        }
        return JSONObject()
            .put("versione", VERSIONE)
            .put("cartella", cartella ?: JSONObject.NULL)
            .put("immagini", immagini ?: JSONObject.NULL)
            .put("note", n)
    }

    companion object {
        const val VERSIONE = 2

        /** Legge il file; se manca o e' rovinato si riparte senza collegamenti (i documenti su Drive restano). */
        fun leggi(testo: String?): DocsLinks {
            val j = try {
                testo?.let { JSONObject(it) } ?: return DocsLinks()
            } catch (_: JSONException) {
                return DocsLinks()
            }
            val prima = j.optInt("versione", 1) < 2
            val note = LinkedHashMap<String, CollegamentoDocs>()
            val n = j.optJSONObject("note")
            n?.keys()?.forEach { id ->
                val c = n.optJSONObject(id) ?: return@forEach
                val documento = c.stringa("documento") ?: return@forEach
                note[id] = if (prima) {
                    // Versione 1: il documento e' tutto di Tratto (titolo e una immagine per pagina).
                    CollegamentoDocs(documento, vecchio = true)
                } else {
                    CollegamentoDocs(documento, blocchi(c.optJSONArray("blocchi")), firme(c.optJSONObject("firme")), c.optBoolean("vecchio"), disegni(c.optJSONArray("disegni")), c.stringa("titolo"))
                }
            }
            return DocsLinks(note, j.stringa("cartella"), j.stringa("immagini"))
        }

        private fun blocchi(a: JSONArray?): List<BloccoSalvato> {
            if (a == null) return emptyList()
            val out = ArrayList<BloccoSalvato>()
            val viste = HashSet<String>()
            for (i in 0 until a.length()) {
                val b = a.optJSONObject(i) ?: continue
                val chiave = b.stringa("chiave") ?: continue
                if (!viste.add(chiave)) continue
                val figura = b.optBoolean("figura")
                val par = b.optJSONArray("paragrafi")
                out += BloccoSalvato(
                    chiave = chiave,
                    figura = figura,
                    pagina = b.stringa("pagina"),
                    impronta = b.optString("impronta"),
                    aspetto = b.optString("aspetto"),
                    oggetto = b.stringa("oggetto"),
                    png = b.stringa("png"),
                    intervallo = b.stringa("intervallo"),
                    paragrafi = if (par == null) emptyList() else (0 until par.length()).map { par.optString(it) },
                    larghezzaPt = b.optDouble("larghezza", 0.0),
                    altezzaPt = b.optDouble("altezza", 0.0),
                )
            }
            return out
        }

        private fun disegni(a: JSONArray?): List<DisegnoSalvato> {
            if (a == null) return emptyList()
            val out = ArrayList<DisegnoSalvato>()
            for (i in 0 until a.length()) {
                val d = a.optJSONObject(i) ?: continue
                out += DisegnoSalvato(d.stringa("chiave") ?: continue, d.optString("pagina"), d.stringa("oggetto") ?: continue, d.optString("impronta"))
            }
            return out
        }

        private fun firme(o: JSONObject?): Map<String, String> {
            if (o == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            o.keys().forEach { k -> o.stringa(k)?.let { out[k] = it } }
            return out
        }

        /** Stringa non vuota o null (optString di Android da' "null" per i valori null). */
        private fun JSONObject.stringa(nome: String): String? =
            if (!has(nome) || isNull(nome)) null else optString(nome).takeIf { it.isNotEmpty() }
    }
}

/** Firma economica di una pagina, senza leggere i tratti: vedi [firma]. */
internal object FirmaPagina {
    /**
     * Data e dimensione dei file della pagina (tratti e oggetti), la pagina stessa e la versione del
     * modo di dividerla. Null se il file dei tratti non c'e': allora la pagina si legge sempre.
     * Va presa prima di leggere i dati: se un file cambia nel frattempo, la volta dopo non coincide.
     */
    fun firma(tratti: File, oggetti: File, pagina: Any): String? {
        if (!tratti.isFile) return null
        val o = if (oggetti.isFile) "${oggetti.lastModified()}:${oggetti.length()}" else "-"
        return "${PageBlocks.VERSIONE}|${tratti.lastModified()}:${tratti.length()}|$o|$pagina"
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
