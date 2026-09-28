package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/*
 * Il documento Google visto da Tratto, in JSON grezzo con org.json come il backup: lettura della
 * risposta di documents.get e corpi delle richieste di documents.batchUpdate. Niente classi Android,
 * cosi' tutto si prova nei test JVM.
 *
 * Gli indici del documento sono in unita' UTF-16, come la lunghezza delle String di Kotlin. L'indice 0
 * e' l'interruzione di sezione iniziale; il corpo comincia a 1 e finisce con un a capo che non si
 * puo' cancellare.
 */

/**
 * Un elemento del corpo del documento, da [inizio] a [fine] esclusa: un paragrafo, oppure qualcosa
 * che Tratto non tocca mai (tabella, sommario...). [testo] e' il testo del paragrafo senza l'a capo
 * finale e senza le immagini; [oggetti] le immagini, con il loro indice; [altro] dice se ci sono
 * elementi che non sono ne' testo ne' immagini. [stile] e' il paragraphStyle letto.
 */
internal data class ElementoDoc(
    val inizio: Int,
    val fine: Int,
    val paragrafo: Boolean,
    val testo: String = "",
    val oggetti: List<Pair<String, Int>> = emptyList(),
    val altro: Boolean = false,
    val stile: JSONObject? = null,
) {
    /** L'id dell'immagine, se il paragrafo contiene solo quella. */
    val soloImmagine: String?
        get() = if (paragrafo && !altro && testo.isEmpty() && oggetti.size == 1) oggetti[0].first else null
}

/** Un intervallo con nome del documento (namedRange): id, nome e l'estensione di tutte le sue parti. */
internal data class IntervalloDoc(val id: String, val nome: String, val inizio: Int, val fine: Int)

/** La risposta di documents.get, letta. */
internal class DocLetto(val revisione: String?, val titolo: String, val elementi: List<ElementoDoc>, val intervalli: List<IntervalloDoc>) {
    /** endIndex dell'ultimo paragrafo: il corpo finisce con l'a capo in [fine] - 1. */
    val fine: Int get() = elementi.lastOrNull()?.fine ?: 2

    companion object {
        /** Campi da chiedere a documents.get: solo quelli che servono. */
        const val CAMPI = "revisionId,title,namedRanges,body(content(startIndex,endIndex," +
            "paragraph(paragraphStyle(namedStyleType,alignment,indentStart,indentFirstLine,spaceAbove)," +
            "elements(startIndex,textRun(content),inlineObjectElement(inlineObjectId)))))"

        fun da(doc: JSONObject): DocLetto {
            val elementi = ArrayList<ElementoDoc>()
            val a = doc.optJSONObject("body")?.optJSONArray("content") ?: JSONArray()
            for (k in 0 until a.length()) {
                val e = a.optJSONObject(k) ?: continue
                val inizio = e.optInt("startIndex", 0)
                val fine = e.optInt("endIndex", 0)
                // L'interruzione di sezione iniziale (senza startIndex) non fa parte del testo.
                if (fine <= inizio || (inizio == 0 && !e.has("paragraph"))) continue
                val p = e.optJSONObject("paragraph")
                if (p == null) {
                    elementi += ElementoDoc(inizio, fine, paragrafo = false) // tabella, sommario, interruzione di sezione
                    continue
                }
                val testo = StringBuilder()
                val oggetti = ArrayList<Pair<String, Int>>()
                var altro = false
                val el = p.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until el.length()) {
                    val x = el.optJSONObject(i) ?: continue
                    val run = x.optJSONObject("textRun")
                    val img = x.optJSONObject("inlineObjectElement")
                    when {
                        run != null -> testo.append(run.optString("content"))
                        img != null -> oggetti += img.optString("inlineObjectId") to x.optInt("startIndex", inizio)
                        else -> altro = true // interruzione di pagina, persona, equazione...
                    }
                }
                if (testo.endsWith("\n")) testo.setLength(testo.length - 1)
                elementi += ElementoDoc(inizio, fine, true, testo.toString(), oggetti, altro, p.optJSONObject("paragraphStyle"))
            }
            val intervalli = ArrayList<IntervalloDoc>()
            val nr = doc.optJSONObject("namedRanges")
            nr?.keys()?.forEach { nome ->
                val gruppo = nr.optJSONObject(nome)?.optJSONArray("namedRanges") ?: return@forEach
                for (i in 0 until gruppo.length()) {
                    val r = gruppo.optJSONObject(i) ?: continue
                    val parti = r.optJSONArray("ranges") ?: continue
                    var s = Int.MAX_VALUE
                    var f = Int.MIN_VALUE
                    for (j in 0 until parti.length()) {
                        val x = parti.optJSONObject(j) ?: continue
                        if (x.has("segmentId") && x.optString("segmentId").isNotEmpty()) continue // intestazioni, note
                        s = minOf(s, x.optInt("startIndex", 0))
                        f = maxOf(f, x.optInt("endIndex", 0))
                    }
                    intervalli += IntervalloDoc(r.optString("namedRangeId"), r.optString("name", nome), s, f)
                }
            }
            return DocLetto(doc.optString("revisionId").takeIf { it.isNotEmpty() }, doc.optString("title"), elementi, intervalli)
        }
    }
}

/** Le richieste di documents.batchUpdate usate da Tratto. */
internal object RichiesteDocs {
    fun inserisciTesto(indice: Int, testo: String): JSONObject =
        JSONObject().put("insertText", JSONObject().put("location", posizione(indice)).put("text", testo))

    fun cancella(inizio: Int, fine: Int): JSONObject =
        JSONObject().put("deleteContentRange", JSONObject().put("range", intervallo(inizio, fine)))

    /** Immagine in linea di [larghezzaPt] x [altezzaPt] punti: Google la scarica una volta da [uri] e ne tiene una copia. */
    fun inserisciImmagine(indice: Int, uri: String, larghezzaPt: Double, altezzaPt: Double): JSONObject =
        JSONObject().put(
            "insertInlineImage",
            JSONObject()
                .put("location", posizione(indice))
                .put("uri", uri)
                .put("objectSize", JSONObject().put("width", misura(larghezzaPt)).put("height", misura(altezzaPt))),
        )

    /** Intervallo con nome: Google lo tiene attaccato al testo mentre il documento cambia. La risposta ne da' l'id. */
    fun creaIntervallo(nome: String, inizio: Int, fine: Int): JSONObject =
        JSONObject().put("createNamedRange", JSONObject().put("name", nome).put("range", intervallo(inizio, fine)))

    fun togliIntervallo(id: String): JSONObject =
        JSONObject().put("deleteNamedRange", JSONObject().put("namedRangeId", id))

    /**
     * Stile dei paragrafi che toccano [inizio, fine). I campi in [campi] senza valore in [stile]
     * tornano a quelli dello stile con nome del paragrafo.
     */
    fun stileParagrafo(inizio: Int, fine: Int, stile: JSONObject, campi: String): JSONObject =
        JSONObject().put(
            "updateParagraphStyle",
            JSONObject().put("range", intervallo(inizio, fine)).put("paragraphStyle", stile).put("fields", campi),
        )

    /** Formato del testo in [inizio, fine): come [stileParagrafo], i campi senza valore tornano a quelli ereditati. */
    fun stileTesto(inizio: Int, fine: Int, stile: JSONObject, campi: String): JSONObject =
        JSONObject().put(
            "updateTextStyle",
            JSONObject().put("range", intervallo(inizio, fine)).put("textStyle", stile).put("fields", campi),
        )

    /** Elenco puntato o numerato sui paragrafi di [inizio, fine); i tab all'inizio dei paragrafi danno il livello e spariscono. */
    fun elenco(inizio: Int, fine: Int, modello: String): JSONObject =
        JSONObject().put("createParagraphBullets", JSONObject().put("range", intervallo(inizio, fine)).put("bulletPreset", modello))

    /** Pagina A4 verticale, come le pagine di Tratto. */
    fun paginaA4(): JSONObject =
        JSONObject().put(
            "updateDocumentStyle",
            JSONObject()
                .put("documentStyle", JSONObject().put("pageSize", JSONObject().put("width", misura(A4_LARGHEZZA_PT)).put("height", misura(A4_ALTEZZA_PT))))
                .put("fields", "pageSize"),
        )

    /**
     * Corpo di documents.batchUpdate. Con [revisione] Google applica le richieste solo se il documento
     * e' ancora quello letto: gli indici sono stati calcolati su quello.
     */
    fun corpo(richieste: JSONArray, revisione: String?): JSONObject {
        val c = JSONObject().put("requests", richieste)
        if (revisione != null) c.put("writeControl", JSONObject().put("requiredRevisionId", revisione))
        return c
    }

    fun misura(pt: Double): JSONObject = JSONObject().put("magnitude", pt).put("unit", "PT")

    /** Colore ARGB di Android come rgbColor di Google (l'alfa si perde). */
    fun colore(argb: Int): JSONObject = JSONObject().put(
        "color",
        JSONObject().put(
            "rgbColor",
            JSONObject()
                .put("red", ((argb shr 16) and 0xFF) / 255.0)
                .put("green", ((argb shr 8) and 0xFF) / 255.0)
                .put("blue", (argb and 0xFF) / 255.0),
        ),
    )

    private fun posizione(indice: Int) = JSONObject().put("index", indice)
    private fun intervallo(inizio: Int, fine: Int) = JSONObject().put("startIndex", inizio).put("endIndex", fine)

    const val A4_LARGHEZZA_PT = 595.28
    const val A4_ALTEZZA_PT = 841.89
}
