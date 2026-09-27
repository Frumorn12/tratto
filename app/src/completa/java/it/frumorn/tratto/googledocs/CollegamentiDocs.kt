package it.frumorn.tratto.googledocs

import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Tratto
import org.json.JSONException
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Una pagina di Tratto gia' nel documento Google. */
internal data class PaginaDocs(
    /** Id dell'immagine nel documento (inlineObjectId). */
    val oggetto: String,
    /** Dimensione della pagina quando l'immagine e' stata inserita (vedi [Voluto.Immagine.dimensione]). */
    val dimensione: String,
    /** [ImprontaPagina] dei dati della pagina disegnata: finche' non cambia non si ridisegna niente. */
    val impronta: String,
    /** SHA-256 del PNG caricato: se il disegno nuovo e' identico non si ricarica. */
    val png: String,
    /**
     * Firma economica dei dati quando si e' calcolata l'[impronta] (data e dimensione del file dei
     * tratti, la pagina, il testo): finche' non cambia non si rileggono nemmeno i tratti.
     */
    val firma: String = "",
)

/** Una nota collegata: il suo documento Google e le pagine che ci sono dentro. */
internal data class CollegamentoDocs(val documento: String, val pagine: Map<String, PaginaDocs> = emptyMap()) {
    /** Id dell'immagine -> [chiaveImmagine], per riconoscere le immagini di Tratto nel documento. */
    fun immagini(): Map<String, String> = pagine.entries.associate { (p, v) -> v.oggetto to chiaveImmagine(p, v.dimensione) }
}

/**
 * Le note collegate a Google Docs, salvate in files/google-docs/collegamenti.json:
 *
 *   {"versione": 1, "cartella": "<id di Tratto su Drive>", "immagini": "<id della cartella temporanea>",
 *    "note": {"<notaId>": {"documento": "<id del documento>",
 *                          "pagine": {"<paginaId>": {"oggetto": .., "dimensione": .., "impronta": .., "png": .., "firma": ..}}}}}
 *
 * Il file sta fuori da files/tratto apposta: e' lo stato della sincronizzazione di questo tablet,
 * come files/backup/drive.json, e non deve finire nei backup ne' essere sostituito da un ripristino.
 * Il modello delle note (nota.json, indice.json) non cambia.
 */
internal data class CollegamentiDocs(
    val note: Map<String, CollegamentoDocs> = emptyMap(),
    /** Cartella "Tratto" su Drive, la stessa del backup. */
    val cartella: String? = null,
    /** Sottocartella delle immagini temporanee. */
    val immagini: String? = null,
) {
    fun json(): JSONObject {
        val n = JSONObject()
        for ((id, c) in note) {
            val p = JSONObject()
            for ((pid, v) in c.pagine) {
                p.put(
                    pid,
                    JSONObject().put("oggetto", v.oggetto).put("dimensione", v.dimensione).put("impronta", v.impronta)
                        .put("png", v.png).put("firma", v.firma),
                )
            }
            n.put(id, JSONObject().put("documento", c.documento).put("pagine", p))
        }
        return JSONObject()
            .put("versione", VERSIONE)
            .put("cartella", cartella ?: JSONObject.NULL)
            .put("immagini", immagini ?: JSONObject.NULL)
            .put("note", n)
    }

    companion object {
        const val VERSIONE = 1

        /** Legge il file; se manca o e' rovinato si riparte senza collegamenti (i documenti su Drive restano). */
        fun leggi(testo: String?): CollegamentiDocs {
            val j = try {
                testo?.let { JSONObject(it) } ?: return CollegamentiDocs()
            } catch (_: JSONException) {
                return CollegamentiDocs()
            }
            val note = LinkedHashMap<String, CollegamentoDocs>()
            val n = j.optJSONObject("note")
            n?.keys()?.forEach { id ->
                val c = n.optJSONObject(id) ?: return@forEach
                val documento = c.stringa("documento") ?: return@forEach
                val pagine = LinkedHashMap<String, PaginaDocs>()
                val p = c.optJSONObject("pagine")
                p?.keys()?.forEach { pid ->
                    val v = p.optJSONObject(pid) ?: return@forEach
                    val oggetto = v.stringa("oggetto") ?: return@forEach
                    pagine[pid] = PaginaDocs(oggetto, v.optString("dimensione"), v.optString("impronta"), v.optString("png"), v.optString("firma"))
                }
                note[id] = CollegamentoDocs(documento, pagine)
            }
            return CollegamentiDocs(note, j.stringa("cartella"), j.stringa("immagini"))
        }

        /** Stringa non vuota o null (optString di Android da' "null" per i valori null). */
        private fun JSONObject.stringa(nome: String): String? =
            if (isNull(nome)) null else optString(nome).takeIf { it.isNotEmpty() }
    }
}

/**
 * Impronta dei dati da cui si disegna una pagina: tratti, sfondo, dimensioni, pagina del PDF e il
 * testo aggiunto sotto. Se non cambia, l'immagine nel documento e' ancora giusta e non si ridisegna.
 * Pagina e' una data class: il suo toString() contiene anche i campi che verranno aggiunti.
 */
internal object ImprontaPagina {
    /** Da aumentare quando cambia il modo di disegnare le pagine: si ridisegnano tutte. */
    const val VERSIONE = 1

    fun di(pagina: Pagina, tratti: List<Tratto>, testo: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(OutputStream.nullOutputStream(), md)).use { d ->
            d.writeInt(VERSIONE)
            scrivi(d, pagina.toString())
            d.writeInt(tratti.size)
            for (t in tratti) {
                d.writeLong(t.id)
                d.writeInt(t.penna.ordinal)
                d.writeInt(t.colore)
                d.writeFloat(t.spessore)
                d.writeInt(t.punti.size)
                for (f in t.punti) d.writeFloat(f)
            }
            d.writeInt(testo.size)
            testo.forEach { scrivi(d, it) }
        }
        return esadecimale(md.digest())
    }

    /**
     * Firma economica, senza leggere i tratti: data e dimensione del loro [file], la pagina e il testo.
     * Null se il file non c'e' (pagina vuota mai salvata, o un formato diverso): allora si legge sempre.
     * Va presa prima di leggere i tratti: se il file cambia nel frattempo, la volta dopo non coincide.
     */
    fun firma(file: File, pagina: Pagina, testo: List<String>): String? =
        if (file.isFile) "${file.lastModified()}:${file.length()}:$pagina:${testo.hashCode()}" else null

    fun sha256(b: ByteArray): String = esadecimale(MessageDigest.getInstance("SHA-256").digest(b))

    private fun scrivi(d: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeInt(b.size)
        d.write(b)
    }

    private fun esadecimale(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
