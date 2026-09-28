package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Documento Google finto per i test: il corpo come sequenza di caratteri e immagini, con le regole
 * di documents.batchUpdate che contano per Tratto. Una richiesta fuori dalle regole (indice fuori dal
 * corpo, cancellazione dell'ultimo a capo, intervallo inesistente, revisione vecchia...) fa fallire il test.
 *
 * Lo stile di paragrafo sta nell'a capo che chiude il paragrafo. Quando una cancellazione unisce due
 * paragrafi, con [stileDalPrimo] il paragrafo unito prende lo stile del primo, altrimenti quello
 * dell'a capo che resta: Tratto deve funzionare in tutti e due i casi.
 *
 * Gli intervalli con nome seguono il testo. Un inserimento dentro un intervallo lo allarga; uno
 * proprio sul bordo lo allarga solo con [allargaInizio] / [allargaFine]: la documentazione di Google
 * non lo dice, e Tratto deve funzionare in tutti i casi.
 */
class DocFinto(
    private val stileDalPrimo: Boolean = false,
    private val allargaInizio: Boolean = false,
    private val allargaFine: Boolean = false,
) {
    class Immagine(val id: String, var uri: String)

    private class Unita(val car: Char, val immagine: Immagine? = null, var stile: JSONObject = JSONObject().put("namedStyleType", "NORMAL_TEXT")) {
        var testo: JSONObject = JSONObject()
        var elenco: String? = null
    }

    class Intervallo(val id: String, val nome: String, var inizio: Int, var fine: Int)

    /** Un paragrafo come lo vede il test. */
    data class Paragrafo(val inizio: Int, val fine: Int, val testo: String, val immagini: List<Immagine>, val stile: String, val allineamento: String) {
        /** Il paragrafo contiene solo un'immagine. */
        val figura get() = testo.isEmpty() && immagini.size == 1
    }

    // Documento nuovo: un solo paragrafo vuoto. L'unita' in posizione k ha indice k + 1.
    private val u = arrayListOf(Unita('\n'))
    private var prossimoOggetto = 1
    private var prossimoIntervallo = 1
    val intervalli = ArrayList<Intervallo>()
    var revisione = 1
        private set
    var titolo = "Documento senza titolo"

    /** Indice subito dopo l'ultimo a capo (endIndex dell'ultimo paragrafo). */
    val fine: Int get() = u.size + 1

    /** Applica le richieste e restituisce la risposta, con le repliche come Google. */
    fun applica(corpo: JSONObject): JSONObject {
        val wc = corpo.optJSONObject("writeControl")
        if (wc != null) check(wc.getString("requiredRevisionId") == revisione.toString()) { "Revisione vecchia" }
        val richieste = corpo.getJSONArray("requests")
        val repliche = JSONArray()
        for (i in 0 until richieste.length()) repliche.put(applicaUna(richieste.getJSONObject(i)))
        revisione++
        return JSONObject().put("replies", repliche).put("writeControl", JSONObject().put("requiredRevisionId", revisione.toString()))
    }

    private fun applicaUna(r: JSONObject): JSONObject {
        check(r.length() == 1) { "Una richiesta per oggetto: $r" }
        val nome = r.keys().next()
        val x = r.getJSONObject(nome)
        when (nome) {
            "insertText" -> inserisciTesto(x.getJSONObject("location").getInt("index"), x.getString("text"))
            "deleteContentRange" -> x.getJSONObject("range").let { cancella(it.getInt("startIndex"), it.getInt("endIndex")) }
            "insertInlineImage" -> {
                val size = x.getJSONObject("objectSize")
                check(size.getJSONObject("width").getString("unit") == "PT" && size.getJSONObject("height").getDouble("magnitude") > 0)
                val id = inserisciImmagine(x.getJSONObject("location").getInt("index"), x.getString("uri"))
                return JSONObject().put("insertInlineImage", JSONObject().put("objectId", id))
            }
            "createNamedRange" -> {
                val rg = x.getJSONObject("range")
                val n = x.getString("name")
                check(n.isNotEmpty() && n.length <= 256) { "Nome di intervallo non valido: $n" }
                val a = rg.getInt("startIndex")
                val b = rg.getInt("endIndex")
                check(a >= 1 && a < b && b <= fine) { "createNamedRange fuori dal corpo: [$a, $b) (fine $fine)" }
                val id = "kix.nr${prossimoIntervallo++}"
                intervalli += Intervallo(id, n, a, b)
                return JSONObject().put("createNamedRange", JSONObject().put("namedRangeId", id))
            }
            "deleteNamedRange" -> {
                val id = x.getString("namedRangeId")
                check(intervalli.removeIf { it.id == id }) { "Intervallo $id inesistente" }
            }
            "updateParagraphStyle" -> {
                val rg = x.getJSONObject("range")
                stile(rg.getInt("startIndex"), rg.getInt("endIndex"), x.getJSONObject("paragraphStyle"), x.getString("fields"))
            }
            "updateTextStyle" -> {
                val rg = x.getJSONObject("range")
                val a = rg.getInt("startIndex")
                val b = rg.getInt("endIndex")
                check(a >= 1 && a < b && b <= fine) { "updateTextStyle fuori dal corpo: [$a, $b)" }
                val campi = x.getString("fields").split(',')
                val ts = x.getJSONObject("textStyle")
                for (k in ts.keys()) check(k in campi) { "Campo $k non nella maschera" }
                for (p in a - 1 until b - 1) u[p].testo = JSONObject(ts.toString())
            }
            "createParagraphBullets" -> {
                val rg = x.getJSONObject("range")
                elenco(rg.getInt("startIndex"), rg.getInt("endIndex"), x.getString("bulletPreset"))
            }
            "updateDocumentStyle" -> check(x.getString("fields") == "pageSize")
            else -> error("Richiesta sconosciuta: $nome")
        }
        return JSONObject()
    }

    fun inserisciTesto(indice: Int, testo: String) {
        check(indice in 1 until fine) { "insertText fuori dal corpo: $indice (fine $fine)" }
        check(testo.isNotEmpty()) { "insertText vuoto" }
        check(testo.none { it != '\n' && it != '\t' && (it.isISOControl() || Character.getType(it) == Character.PRIVATE_USE.toInt()) }) { "Carattere che Google toglierebbe" }
        val p = indice - 1
        val chiuso = chiusura(p)
        testo.forEachIndexed { k, c -> u.add(p + k, if (c == '\n') Unita(c, null, JSONObject(chiuso.stile.toString())) else Unita(c)) }
        spostaIntervalli(indice, testo.length)
    }

    fun inserisciImmagine(indice: Int, uri: String): String {
        check(indice in 1 until fine) { "insertInlineImage fuori dal corpo: $indice (fine $fine)" }
        val id = "kix.${prossimoOggetto++}"
        u.add(indice - 1, Unita('￼', Immagine(id, controllaUri(uri))))
        spostaIntervalli(indice, 1)
        return id
    }

    fun cancella(inizio: Int, fine: Int) {
        check(inizio >= 1 && inizio < fine) { "deleteContentRange non valido: [$inizio, $fine)" }
        check(fine <= this.fine - 1) { "deleteContentRange cancellerebbe l'ultimo a capo: [$inizio, $fine) con fine ${this.fine}" }
        val primo = JSONObject(chiusura(inizio - 1).stile.toString())
        val tolti = u.subList(inizio - 1, fine - 1)
        val unisce = tolti.any { it.car == '\n' }
        tolti.clear()
        if (unisce && stileDalPrimo) chiusura(inizio - 1).stile = primo
        val l = fine - inizio
        intervalli.removeIf { r ->
            r.inizio = if (r.inizio >= fine) r.inizio - l else if (r.inizio > inizio) inizio else r.inizio
            r.fine = if (r.fine >= fine) r.fine - l else if (r.fine > inizio) inizio else r.fine
            r.fine <= r.inizio
        }
    }

    private fun spostaIntervalli(indice: Int, l: Int) {
        for (r in intervalli) {
            when {
                indice < r.inizio || (indice == r.inizio && !allargaInizio) -> { r.inizio += l; r.fine += l }
                indice < r.fine || (indice == r.fine && allargaFine) -> r.fine += l
            }
        }
    }

    /** Stile dei paragrafi che toccano [inizio, fine): i campi senza valore tornano quelli predefiniti. */
    fun stile(inizio: Int, fine: Int, stile: JSONObject, campi: String) {
        check(inizio >= 1 && inizio < fine && fine <= this.fine) { "updateParagraphStyle fuori dal corpo: [$inizio, $fine)" }
        val c = campi.split(',')
        for (k in stile.keys()) check(k in c) { "Campo $k non nella maschera" }
        val paragrafi = LinkedHashSet<Unita>()
        for (p in inizio - 1 until fine - 1) paragrafi += chiusura(p)
        for (x in paragrafi) {
            for (k in c) if (stile.has(k)) x.stile.put(k, stile.get(k)) else x.stile.remove(k)
            if (!x.stile.has("namedStyleType")) x.stile.put("namedStyleType", "NORMAL_TEXT")
        }
    }

    /** Elenco sui paragrafi di [inizio, fine): i tab all'inizio dei paragrafi spariscono (e gli indici cambiano). */
    private fun elenco(inizio: Int, fine: Int, modello: String) {
        check(inizio >= 1 && inizio < fine && fine <= this.fine) { "createParagraphBullets fuori dal corpo: [$inizio, $fine)" }
        val chiusure = LinkedHashSet<Unita>()
        for (p in inizio - 1 until fine - 1) chiusure += chiusura(p)
        for (x in chiusure) x.elenco = modello
        // Dal fondo: togliere i tab sposta solo quello che viene dopo.
        for (x in chiusure.reversed()) {
            var k = u.indexOf(x)
            while (k > 0 && u[k - 1].car != '\n') k--
            while (u[k].car == '\t') cancella(k + 1, k + 2)
        }
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
                parte.mapNotNull { it.immagine }, x.stile.getString("namedStyleType"), x.stile.optString("alignment", "START"),
            )
            inizio = k + 1
        }
        return out
    }

    /** Lo stile di paragrafo del paragrafo che comincia in [inizio]. */
    fun stileDi(inizio: Int): JSONObject = chiusura(inizio - 1).stile

    /** Il formato del testo del carattere in [indice]. */
    fun testoDi(indice: Int): JSONObject = u[indice - 1].testo

    fun elencoDi(inizio: Int): String? = chiusura(inizio - 1).elenco

    /** Il testo dell'intervallo con nome [nome] (le immagini come ￼). */
    fun testoIntervallo(nome: String): String? = intervalli.firstOrNull { it.nome == nome }?.let { r -> u.subList(r.inizio - 1, r.fine - 1).map { it.car }.joinToString("") }

    /** La risposta di documents.get con i campi di [DocLetto.CAMPI]. */
    fun json(): JSONObject {
        val content = JSONArray().put(JSONObject().put("endIndex", 1).put("sectionBreak", JSONObject()))
        var inizio = 0
        for ((k, x) in u.withIndex()) {
            if (x.car != '\n') continue
            val elementi = JSONArray()
            val testo = StringBuilder()
            var inizioTesto = inizio + 1
            for ((i, y) in u.subList(inizio, k + 1).withIndex()) {
                if (y.immagine != null) {
                    if (testo.isNotEmpty()) elementi.put(JSONObject().put("startIndex", inizioTesto).put("textRun", JSONObject().put("content", testo.toString())))
                    testo.setLength(0)
                    elementi.put(JSONObject().put("startIndex", inizio + 1 + i).put("inlineObjectElement", JSONObject().put("inlineObjectId", y.immagine.id)))
                    inizioTesto = inizio + 2 + i
                } else {
                    testo.append(y.car)
                }
            }
            elementi.put(JSONObject().put("startIndex", inizioTesto).put("textRun", JSONObject().put("content", testo.toString())))
            // Come Google: i valori predefiniti non si ripetono.
            val stile = JSONObject(x.stile.toString())
            if (stile.optString("alignment") == "START") stile.remove("alignment")
            content.put(
                JSONObject().put("startIndex", inizio + 1).put("endIndex", k + 2)
                    .put("paragraph", JSONObject().put("paragraphStyle", stile).put("elements", elementi)),
            )
            inizio = k + 1
        }
        val nr = JSONObject()
        for (r in intervalli) {
            val g = nr.optJSONObject(r.nome) ?: JSONObject().put("name", r.nome).put("namedRanges", JSONArray()).also { nr.put(r.nome, it) }
            g.getJSONArray("namedRanges").put(
                JSONObject().put("namedRangeId", r.id).put("name", r.nome)
                    .put("ranges", JSONArray().put(JSONObject().put("startIndex", r.inizio).put("endIndex", r.fine))),
            )
        }
        return JSONObject().put("revisionId", revisione.toString()).put("title", titolo)
            .put("body", JSONObject().put("content", content)).put("namedRanges", nr)
    }

    /** Qualcuno scrive nel documento da Google Docs (non passa da batchUpdate ma cambia la revisione). */
    fun aMano(f: DocFinto.() -> Unit) {
        f()
        revisione++
    }
}
