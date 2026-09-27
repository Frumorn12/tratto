package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Documento Google finto per i test: il corpo come sequenza di caratteri e immagini, con le regole
 * di documents.batchUpdate che contano per Tratto. Una richiesta fuori dalle regole (indice fuori dal
 * corpo, cancellazione dell'ultimo a capo, revisione vecchia...) fa fallire il test.
 *
 * Lo stile di paragrafo sta nell'a capo che chiude il paragrafo. Quando una cancellazione unisce due
 * paragrafi, con [stileDalPrimo] il paragrafo unito prende lo stile del primo, altrimenti quello
 * dell'a capo che resta: Tratto deve funzionare in tutti e due i casi.
 */
class DocFinto(private val stileDalPrimo: Boolean = false) {
    class Immagine(val id: String, var uri: String)

    private class Unita(val car: Char, val immagine: Immagine? = null, var stile: String = "NORMAL_TEXT", var allineamento: String = "START")

    /** Un paragrafo come lo vede il test. */
    data class Paragrafo(val inizio: Int, val fine: Int, val testo: String, val immagini: List<Immagine>, val stile: String, val allineamento: String)

    // Documento nuovo: un solo paragrafo vuoto. L'unita' in posizione k ha indice k + 1.
    private val u = arrayListOf(Unita('\n'))
    private var prossimoOggetto = 1
    var revisione = 1
        private set
    var titolo = "Documento senza titolo"

    /** Indice subito dopo l'ultimo a capo (endIndex dell'ultimo paragrafo). */
    val fine: Int get() = u.size + 1

    fun applica(corpo: JSONObject) {
        val wc = corpo.optJSONObject("writeControl")
        if (wc != null) check(wc.getString("requiredRevisionId") == revisione.toString()) { "Revisione vecchia" }
        val richieste = corpo.getJSONArray("requests")
        for (i in 0 until richieste.length()) applicaUna(richieste.getJSONObject(i))
        revisione++
    }

    private fun applicaUna(r: JSONObject) {
        check(r.length() == 1) { "Una richiesta per oggetto: $r" }
        val nome = r.keys().next()
        val x = r.getJSONObject(nome)
        when (nome) {
            "insertText" -> inserisciTesto(x.getJSONObject("location").getInt("index"), x.getString("text"))
            "deleteContentRange" -> x.getJSONObject("range").let { cancella(it.getInt("startIndex"), it.getInt("endIndex")) }
            "insertInlineImage" -> {
                val size = x.getJSONObject("objectSize")
                check(size.getJSONObject("width").getString("unit") == "PT" && size.getJSONObject("height").getDouble("magnitude") > 0)
                inserisciImmagine(x.getJSONObject("location").getInt("index"), x.getString("uri"))
            }
            "replaceImage" -> {
                check(x.getString("imageReplaceMethod") == "CENTER_CROP")
                val img = immagini().firstOrNull { it.id == x.getString("imageObjectId") } ?: error("Immagine ${x.getString("imageObjectId")} inesistente")
                img.uri = controllaUri(x.getString("uri"))
            }
            "updateParagraphStyle" -> {
                check(x.getString("fields") == "namedStyleType,alignment")
                val rg = x.getJSONObject("range")
                val ps = x.getJSONObject("paragraphStyle")
                stile(rg.getInt("startIndex"), rg.getInt("endIndex"), ps.getString("namedStyleType"), ps.getString("alignment"))
            }
            "updateDocumentStyle" -> check(x.getString("fields") == "pageSize")
            else -> error("Richiesta sconosciuta: $nome")
        }
    }

    fun inserisciTesto(indice: Int, testo: String) {
        check(indice in 1 until fine) { "insertText fuori dal corpo: $indice (fine $fine)" }
        check(testo.isNotEmpty()) { "insertText vuoto" }
        check(testo.none { it != '\n' && (it.isISOControl() || Character.getType(it) == Character.PRIVATE_USE.toInt()) }) { "Carattere che Google toglierebbe" }
        val p = indice - 1
        val chiuso = chiusura(p)
        testo.forEachIndexed { k, c -> u.add(p + k, if (c == '\n') Unita(c, null, chiuso.stile, chiuso.allineamento) else Unita(c)) }
    }

    fun inserisciImmagine(indice: Int, uri: String) {
        check(indice in 1 until fine) { "insertInlineImage fuori dal corpo: $indice (fine $fine)" }
        u.add(indice - 1, Unita('￼', Immagine("kix.${prossimoOggetto++}", controllaUri(uri))))
    }

    fun cancella(inizio: Int, fine: Int) {
        check(inizio >= 1 && inizio < fine) { "deleteContentRange non valido: [$inizio, $fine)" }
        check(fine <= this.fine - 1) { "deleteContentRange cancellerebbe l'ultimo a capo: [$inizio, $fine) con fine ${this.fine}" }
        val primo = chiusura(inizio - 1).let { it.stile to it.allineamento }
        val tolti = u.subList(inizio - 1, fine - 1)
        val unisce = tolti.any { it.car == '\n' }
        tolti.clear()
        if (unisce && stileDalPrimo) chiusura(inizio - 1).let { it.stile = primo.first; it.allineamento = primo.second }
    }

    /** Stile dei paragrafi che toccano [inizio, fine). */
    fun stile(inizio: Int, fine: Int, stile: String, allineamento: String) {
        check(inizio >= 1 && inizio < fine && fine <= this.fine) { "updateParagraphStyle fuori dal corpo: [$inizio, $fine)" }
        for (p in inizio - 1 until fine - 1) chiusura(p).let { it.stile = stile; it.allineamento = allineamento }
    }

    /** L'a capo che chiude il paragrafo in cui sta la posizione [p]. */
    private fun chiusura(p: Int): Unita = u[(p until u.size).first { u[it].car == '\n' }]

    private fun controllaUri(uri: String): String {
        check(uri.startsWith("https://") && uri.length <= 2048) { "URI non valido: $uri" }
        return uri
    }

    fun immagini(): List<Immagine> = u.mapNotNull { it.immagine }

    fun paragrafi(): List<Paragrafo> {
        val out = ArrayList<Paragrafo>()
        var inizio = 0
        for ((k, x) in u.withIndex()) {
            if (x.car != '\n') continue
            val parte = u.subList(inizio, k)
            out += Paragrafo(
                inizio + 1, k + 2,
                parte.filter { it.immagine == null }.map { it.car }.joinToString(""),
                parte.mapNotNull { it.immagine }, x.stile, x.allineamento,
            )
            inizio = k + 1
        }
        return out
    }

    /** La risposta di documents.get con i campi di [LetturaDoc.CAMPI]. */
    fun json(): JSONObject {
        val content = JSONArray().put(JSONObject().put("endIndex", 1).put("sectionBreak", JSONObject()))
        var inizio = 0
        for ((k, x) in u.withIndex()) {
            if (x.car != '\n') continue
            val elementi = JSONArray()
            val testo = StringBuilder()
            for (y in u.subList(inizio, k + 1)) {
                if (y.immagine != null) {
                    if (testo.isNotEmpty()) elementi.put(JSONObject().put("textRun", JSONObject().put("content", testo.toString())))
                    testo.setLength(0)
                    elementi.put(JSONObject().put("inlineObjectElement", JSONObject().put("inlineObjectId", y.immagine.id)))
                } else {
                    testo.append(y.car)
                }
            }
            elementi.put(JSONObject().put("textRun", JSONObject().put("content", testo.toString())))
            // Come Google: l'allineamento predefinito non si ripete.
            val stile = JSONObject().put("namedStyleType", x.stile)
            if (x.allineamento != "START") stile.put("alignment", x.allineamento)
            content.put(
                JSONObject().put("startIndex", inizio + 1).put("endIndex", k + 2)
                    .put("paragraph", JSONObject().put("paragraphStyle", stile).put("elements", elementi)),
            )
            inizio = k + 1
        }
        return JSONObject().put("revisionId", revisione.toString()).put("title", titolo).put("body", JSONObject().put("content", content))
    }
}
