package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/**
 * Il testo di un intervallo con nome, letto dalla risposta di documents.get (con il testo del corpo),
 * e le richieste per portarlo a un testo nuovo. Serve a modificare un paragrafo del documento da
 * Tratto mentre altri ci scrivono: si cambia solo il pezzo diverso, con gli indici della revisione
 * appena letta, e Google unisce le modifiche arrivate nel frattempo (targetRevisionId).
 */
internal object TestoCondiviso {
    /** Un intervallo e il suo testo: [testo] va da [inizio] a [fine] - 1 (l'ultimo a capo non c'e'). */
    class Pezzo(val id: String, val inizio: Int, val fine: Int, val testo: String)

    /** L'intervallo con nome [nome] e il suo testo, o null se non c'e' piu'. */
    fun leggi(doc: JSONObject, nome: String): Pezzo? {
        val r = doc.optJSONObject("namedRanges")?.optJSONObject(nome)?.optJSONArray("namedRanges")?.optJSONObject(0) ?: return null
        val parti = r.optJSONArray("ranges") ?: return null
        var s = Int.MAX_VALUE
        var e = Int.MIN_VALUE
        for (i in 0 until parti.length()) {
            val x = parti.optJSONObject(i) ?: continue
            s = minOf(s, x.optInt("startIndex", 0))
            e = maxOf(e, x.optInt("endIndex", 0))
        }
        if (s >= e) return null
        val caratteri = CharArray(e - s) { '\u0000' }
        caratteri(doc.optJSONObject("body")?.optJSONArray("content")) { indice, c -> if (indice in s until e) caratteri[indice - s] = c }
        // L'ultimo carattere e' l'a capo del paragrafo: non fa parte del testo. Quello che non e' testo
        // (persone, equazioni...) resta un segnaposto, cosi' le posizioni restano quelle del documento.
        val testo = String(caratteri, 0, caratteri.size - 1).replace('\u0000', '\uFFFC')
        return Pezzo(r.optString("namedRangeId"), s, e, testo)
    }

    /** Ogni carattere del corpo con il suo indice (le immagini come "￼"), anche dentro le tabelle. */
    private fun caratteri(a: JSONArray?, f: (Int, Char) -> Unit) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            e.optJSONObject("paragraph")?.optJSONArray("elements")?.let { el ->
                for (j in 0 until el.length()) {
                    val x = el.optJSONObject(j) ?: continue
                    val si = x.optInt("startIndex", 0)
                    x.optJSONObject("textRun")?.optString("content")?.forEachIndexed { k, c -> f(si + k, c) }
                    if (x.has("inlineObjectElement")) f(si, '￼')
                }
            }
            e.optJSONObject("table")?.optJSONArray("tableRows")?.let { righe ->
                for (r in 0 until righe.length()) {
                    val celle = righe.optJSONObject(r)?.optJSONArray("tableCells") ?: continue
                    for (c in 0 until celle.length()) caratteri(celle.optJSONObject(c)?.optJSONArray("content"), f)
                }
            }
        }
    }

    /**
     * Le richieste per passare da [prima] (il testo dell'intervallo che comincia in [inizio]) a [dopo]:
     * si tiene quello che hanno in comune all'inizio e alla fine e si cambia il resto.
     */
    fun richieste(inizio: Int, prima: String, dopo: String): JSONArray {
        val out = JSONArray()
        if (prima == dopo) return out
        var p = 0
        while (p < prima.length && p < dopo.length && prima[p] == dopo[p]) p++
        var q = 0
        while (q < prima.length - p && q < dopo.length - p && prima[prima.length - 1 - q] == dopo[dopo.length - 1 - q]) q++
        // Una coppia UTF-16 (emoji) non si spezza.
        if (p > 0 && Character.isHighSurrogate(prima[p - 1])) p--
        if (q > 0 && Character.isLowSurrogate(prima[prima.length - q])) q--
        val a = inizio + p
        val b = inizio + prima.length - q
        if (b > a) out.put(RichiesteDocs.cancella(a, b))
        val nuovo = pulisci(dopo.substring(p, dopo.length - q))
        if (nuovo.isNotEmpty()) out.put(RichiesteDocs.inserisciTesto(a, nuovo))
        return out
    }

    /** Il testo che si puo' inserire: a capo e tab restano; niente segnaposto di immagini ne' caratteri che Google toglierebbe. */
    fun pulisci(s: String): String =
        s.replace(Regex("[\\p{Co}￼]"), "").replace(Regex("[\\p{Cntrl}&&[^\n\t]]"), " ")
}
