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

/** Stile dei paragrafi scritti da Tratto: stile Google con nome e allineamento. */
internal enum class StileParagrafo(val nome: String, val allineamento: String) {
    TITOLO("TITLE", "START"),
    TESTO("NORMAL_TEXT", "START"),
    IMMAGINE("NORMAL_TEXT", "CENTER"),
}

/** Chiave di un paragrafo di testo: conta anche lo stile, cosi' il titolo non si confonde con un testo uguale. */
internal fun chiaveTesto(stile: String, testo: String) = "T\u0000$stile\u0000$testo"

/** Chiave di un'immagine di pagina: se cambia la dimensione della pagina l'immagine va rifatta, non sostituita. */
internal fun chiaveImmagine(pagina: String, dimensione: String) = "I\u0000$pagina\u0000$dimensione"

/** Stile e allineamento di un paragrafo, per confrontarli con quelli voluti. */
internal fun aspetto(stile: String, allineamento: String) = "$stile/$allineamento"

internal val StileParagrafo.aspetto: String get() = aspetto(nome, allineamento)

/**
 * Un elemento del corpo del documento com'e' adesso, da [inizio] a [fine] esclusa. [chiave] dice cosa
 * contiene; gli elementi che Tratto non riconosce hanno una chiave unica e non restano mai.
 * [oggetto] e' l'id dell'immagine per i paragrafi che ne contengono solo una; [aspetto] lo stile
 * del paragrafo (vedi [aspetto]).
 */
internal data class ParagrafoDoc(
    val inizio: Int,
    val fine: Int,
    val chiave: String,
    val oggetto: String? = null,
    val aspetto: String? = null,
)

/** Lettura della risposta di documents.get. */
internal object LetturaDoc {
    /** Campi da chiedere: solo quelli che servono a riconoscere i paragrafi. */
    const val CAMPI = "revisionId,title,body(content(startIndex,endIndex," +
        "paragraph(paragraphStyle(namedStyleType,alignment),elements(textRun(content),inlineObjectElement(inlineObjectId)))))"

    /**
     * I paragrafi del corpo, in ordine. [immagini] da' la chiave di ogni immagine messa da Tratto
     * (id dell'oggetto -> [chiaveImmagine]); le altre immagini non si riconoscono.
     */
    fun paragrafi(doc: JSONObject, immagini: Map<String, String>): List<ParagrafoDoc> {
        val out = ArrayList<ParagrafoDoc>()
        for (e in elementi(doc)) {
            val inizio = e.optInt("startIndex", 0)
            val fine = e.optInt("endIndex", 0)
            // L'interruzione di sezione iniziale (senza startIndex) non fa parte del testo.
            if (fine <= inizio || (inizio == 0 && !e.has("paragraph"))) continue
            val sconosciuto = "X\u0000$inizio"
            val p = e.optJSONObject("paragraph")
            if (p == null) {
                out += ParagrafoDoc(inizio, fine, sconosciuto) // tabella, indice, interruzione di sezione
                continue
            }
            val testo = StringBuilder()
            val oggetti = ArrayList<String>()
            var altro = false
            val el = p.optJSONArray("elements") ?: JSONArray()
            for (i in 0 until el.length()) {
                val x = el.optJSONObject(i) ?: continue
                val run = x.optJSONObject("textRun")
                val img = x.optJSONObject("inlineObjectElement")
                when {
                    run != null -> testo.append(run.optString("content"))
                    img != null -> oggetti += img.optString("inlineObjectId")
                    else -> altro = true // interruzione di pagina, persona, equazione...
                }
            }
            if (testo.endsWith("\n")) testo.setLength(testo.length - 1)
            // Senza valore vale quello predefinito: Google non ripete le proprieta' ereditate.
            val ps = p.optJSONObject("paragraphStyle")
            val stile = ps?.optString("namedStyleType")?.takeIf { it.isNotEmpty() } ?: "NORMAL_TEXT"
            val aspetto = aspetto(stile, ps?.optString("alignment")?.takeIf { it.isNotEmpty() } ?: "START")
            out += when {
                altro -> ParagrafoDoc(inizio, fine, sconosciuto)
                oggetti.isEmpty() -> ParagrafoDoc(inizio, fine, chiaveTesto(stile, testo.toString()), aspetto = aspetto)
                oggetti.size == 1 && testo.isEmpty() ->
                    ParagrafoDoc(inizio, fine, immagini[oggetti[0]] ?: sconosciuto, oggetti[0], aspetto)
                else -> ParagrafoDoc(inizio, fine, sconosciuto)
            }
        }
        return out
    }

    /** Gli id delle immagini che stanno da sole in un paragrafo, nell'ordine del documento. */
    fun immagini(doc: JSONObject): List<String> =
        paragrafi(doc, emptyMap()).mapNotNull { it.oggetto }

    private fun elementi(doc: JSONObject): List<JSONObject> {
        val a = doc.optJSONObject("body")?.optJSONArray("content") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
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

    /** Sostituisce l'immagine tenendo le sue dimensioni nel documento: niente spostamenti per chi guarda. */
    fun sostituisciImmagine(oggetto: String, uri: String): JSONObject =
        JSONObject().put(
            "replaceImage",
            JSONObject().put("imageObjectId", oggetto).put("uri", uri).put("imageReplaceMethod", "CENTER_CROP"),
        )

    /** Stile dei paragrafi che toccano [inizio, fine). */
    fun stile(inizio: Int, fine: Int, stile: StileParagrafo): JSONObject =
        JSONObject().put(
            "updateParagraphStyle",
            JSONObject()
                .put("range", intervallo(inizio, fine))
                .put("paragraphStyle", JSONObject().put("namedStyleType", stile.nome).put("alignment", stile.allineamento))
                .put("fields", "namedStyleType,alignment"),
        )

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

    private fun posizione(indice: Int) = JSONObject().put("index", indice)
    private fun intervallo(inizio: Int, fine: Int) = JSONObject().put("startIndex", inizio).put("endIndex", fine)
    private fun misura(pt: Double) = JSONObject().put("magnitude", pt).put("unit", "PT")

    const val A4_LARGHEZZA_PT = 595.28
    const val A4_ALTEZZA_PT = 841.89
}
