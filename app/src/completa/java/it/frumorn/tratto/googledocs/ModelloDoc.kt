package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject

/** Formato di un pezzo di testo del documento, gia' risolto (stile con nome + NORMAL_TEXT + formato del pezzo). */
internal data class FormatoDoc(
    val grassetto: Boolean = false,
    val corsivo: Boolean = false,
    val sottolineato: Boolean = false,
    val barrato: Boolean = false,
    /** ARGB, o null per il nero. */
    val colore: Int? = null,
    val sfondo: Int? = null,
    val punti: Double = 11.0,
    val famiglia: String = "Arial",
    /** SUPERSCRIPT, SUBSCRIPT o null. */
    val spostamento: String? = null,
    val collegamento: Boolean = false,
)

/** Un pezzo di paragrafo: testo con il suo formato, oppure un'immagine in linea ([immagine], testo "￼"). */
internal data class PezzoDoc(val testo: String, val formato: FormatoDoc, val immagine: String? = null)

/**
 * Un paragrafo del documento, con lo stile risolto. Le misure sono in punti. [elenco] e' il simbolo
 * o il numero da mettere davanti (gia' calcolato), [interruzione] dice che dopo il paragrafo si va a
 * pagina nuova, [posizionati] sono gli oggetti ancorati al paragrafo.
 */
internal data class ParagrafoDoc(
    val inizio: Int,
    val fine: Int,
    val pezzi: List<PezzoDoc>,
    val stile: String = "NORMAL_TEXT",
    val allineamento: String = "START",
    val rientroInizio: Double = 0.0,
    val rientroPrimaRiga: Double = 0.0,
    val sopra: Double = 0.0,
    val sotto: Double = 0.0,
    /** Interlinea in percentuale (100 = singola). */
    val interlinea: Double = 115.0,
    val elenco: String? = null,
    val formatoElenco: FormatoDoc? = null,
    val interruzione: Boolean = false,
    val posizionati: List<String> = emptyList(),
    /** Il formato dello stile del paragrafo: da' l'altezza delle righe vuote. */
    val formatoBase: FormatoDoc = FormatoDoc(),
) {
    val testo: String get() = pezzi.joinToString("") { it.testo }
}

/** Un'immagine in linea: misure in punti e indirizzo da cui scaricarla (vale una mezz'ora). */
internal data class ImmagineDoc(val larghezza: Double, val altezza: Double, val uri: String?)

/** Un oggetto posizionato (immagine davanti o dietro al testo), rispetto al paragrafo a cui e' ancorato. */
internal data class PosizionatoDoc(
    val larghezza: Double,
    val altezza: Double,
    val uri: String?,
    val sinistra: Double,
    val alto: Double,
    /** WRAP_TEXT, BREAK_LEFT, BREAK_RIGHT, BREAK_LEFT_RIGHT, IN_FRONT_OF_TEXT, BEHIND_TEXT. */
    val disposizione: String,
)

/** Il documento Google letto per disegnarlo: pagina, margini e paragrafi (tutto in punti). */
internal class ModelloDoc(
    val revisione: String,
    val titolo: String,
    val larghezza: Double,
    val altezza: Double,
    val margineSu: Double,
    val margineGiu: Double,
    val margineSinistro: Double,
    val margineDestro: Double,
    val paragrafi: List<ParagrafoDoc>,
    val immagini: Map<String, ImmagineDoc>,
    val posizionati: Map<String, PosizionatoDoc>,
) {
    companion object {
        /**
         * Legge la risposta completa di documents.get (senza maschera dei campi). Il formato di ogni
         * pezzo e' quello dichiarato sopra lo stile con nome del paragrafo, sopra NORMAL_TEXT, sopra i
         * valori predefiniti di Documenti: Google non ripete le proprieta' ereditate.
         */
        fun da(doc: JSONObject): ModelloDoc {
            val stili = HashMap<String, JSONObject>()
            doc.optJSONObject("namedStyles")?.optJSONArray("styles")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { stili[it.optString("namedStyleType")] = it }
            }
            val ds = doc.optJSONObject("documentStyle") ?: JSONObject()
            val size = ds.optJSONObject("pageSize")
            val liste = doc.optJSONObject("lists") ?: JSONObject()
            val lettore = Lettore(stili, liste)

            val paragrafi = ArrayList<ParagrafoDoc>()
            lettore.contenuto(doc.optJSONObject("body")?.optJSONArray("content"), paragrafi)

            val immagini = HashMap<String, ImmagineDoc>()
            doc.optJSONObject("inlineObjects")?.let { io ->
                for (id in io.keys()) {
                    val e = io.optJSONObject(id)?.optJSONObject("inlineObjectProperties")?.optJSONObject("embeddedObject") ?: continue
                    val s = e.optJSONObject("size")
                    immagini[id] = ImmagineDoc(pt(s?.optJSONObject("width")), pt(s?.optJSONObject("height")), e.optJSONObject("imageProperties")?.optString("contentUri")?.takeIf { it.isNotEmpty() })
                }
            }
            val posizionati = HashMap<String, PosizionatoDoc>()
            doc.optJSONObject("positionedObjects")?.let { po ->
                for (id in po.keys()) {
                    val p = po.optJSONObject(id)?.optJSONObject("positionedObjectProperties") ?: continue
                    val e = p.optJSONObject("embeddedObject") ?: continue
                    val s = e.optJSONObject("size")
                    val pos = p.optJSONObject("positioning")
                    posizionati[id] = PosizionatoDoc(
                        pt(s?.optJSONObject("width")), pt(s?.optJSONObject("height")),
                        e.optJSONObject("imageProperties")?.optString("contentUri")?.takeIf { it.isNotEmpty() },
                        pt(pos?.optJSONObject("leftOffset")), pt(pos?.optJSONObject("topOffset")),
                        pos?.optString("layout")?.takeIf { it.isNotEmpty() } ?: "WRAP_TEXT",
                    )
                }
            }
            return ModelloDoc(
                doc.optString("revisionId"), doc.optString("title"),
                pt(size?.optJSONObject("width"), 612.0), pt(size?.optJSONObject("height"), 792.0),
                pt(ds.optJSONObject("marginTop"), 72.0), pt(ds.optJSONObject("marginBottom"), 72.0),
                pt(ds.optJSONObject("marginLeft"), 72.0), pt(ds.optJSONObject("marginRight"), 72.0),
                paragrafi, immagini, posizionati,
            )
        }

        /** Una Dimension di Google in punti. */
        fun pt(d: JSONObject?, predefinito: Double = 0.0): Double {
            if (d == null || !d.has("magnitude")) return predefinito
            val m = d.optDouble("magnitude", predefinito)
            return if (d.optString("unit") == "PT" || !d.has("unit")) m else predefinito
        }

        /** Un OptionalColor di Google come ARGB, o null. */
        fun colore(c: JSONObject?): Int? {
            val rgb = c?.optJSONObject("color")?.optJSONObject("rgbColor") ?: return null
            fun v(k: String) = (rgb.optDouble(k, 0.0) * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (v("red") shl 16) or (v("green") shl 8) or v("blue")
        }
    }

    /** Legge il corpo risolvendo gli stili; tiene il conto dei numeri degli elenchi. */
    private class Lettore(private val stili: Map<String, JSONObject>, private val liste: JSONObject) {
        /** Per ogni elenco, il numero raggiunto a ogni livello. */
        private val contatori = HashMap<String, IntArray>()

        fun contenuto(a: JSONArray?, out: MutableList<ParagrafoDoc>) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                e.optJSONObject("paragraph")?.let { out += paragrafo(e, it) }
                // Tabelle: per ora i paragrafi delle celle uno dopo l'altro.
                e.optJSONObject("table")?.optJSONArray("tableRows")?.let { righe ->
                    for (r in 0 until righe.length()) {
                        val celle = righe.optJSONObject(r)?.optJSONArray("tableCells") ?: continue
                        for (c in 0 until celle.length()) contenuto(celle.optJSONObject(c)?.optJSONArray("content"), out)
                    }
                }
            }
        }

        private fun paragrafo(e: JSONObject, p: JSONObject): ParagrafoDoc {
            val ps = p.optJSONObject("paragraphStyle") ?: JSONObject()
            val nome = ps.optString("namedStyleType").ifEmpty { "NORMAL_TEXT" }
            val base = unisci(stili["NORMAL_TEXT"]?.optJSONObject("paragraphStyle"), stili[nome]?.optJSONObject("paragraphStyle"), ps)
            val baseTesto = unisci(stili["NORMAL_TEXT"]?.optJSONObject("textStyle"), stili[nome]?.optJSONObject("textStyle"))

            val pezzi = ArrayList<PezzoDoc>()
            var interruzione = false
            val el = p.optJSONArray("elements") ?: JSONArray()
            for (i in 0 until el.length()) {
                val x = el.optJSONObject(i) ?: continue
                val run = x.optJSONObject("textRun")
                val img = x.optJSONObject("inlineObjectElement")
                when {
                    run != null -> {
                        var t = run.optString("content")
                        if (t.endsWith("\n")) t = t.dropLast(1)
                        if (t.isNotEmpty()) pezzi += PezzoDoc(t, formato(unisci(baseTesto, run.optJSONObject("textStyle"))))
                    }
                    img != null -> pezzi += PezzoDoc("￼", formato(unisci(baseTesto, img.optJSONObject("textStyle"))), img.optString("inlineObjectId"))
                    x.has("pageBreak") -> interruzione = true
                    else -> {
                        // Persone, date, equazioni...: il testo che c'e', o niente.
                        val t = x.optJSONObject("person")?.optJSONObject("personProperties")?.optString("name")
                            ?: x.optJSONObject("richLink")?.optJSONObject("richLinkProperties")?.optString("title")
                        if (!t.isNullOrEmpty()) pezzi += PezzoDoc(t, formato(baseTesto))
                    }
                }
            }

            var simbolo: String? = null
            var formatoSimbolo: FormatoDoc? = null
            var inizio = ModelloDoc.pt(base.optJSONObject("indentStart"))
            var primaRiga = ModelloDoc.pt(base.optJSONObject("indentFirstLine"), inizio)
            p.optJSONObject("bullet")?.let { b ->
                val livello = b.optInt("nestingLevel", 0)
                val lista = b.optString("listId")
                val nl = liste.optJSONObject(lista)?.optJSONObject("listProperties")?.optJSONArray("nestingLevels")?.optJSONObject(livello)
                simbolo = simboloElenco(lista, livello, nl)
                formatoSimbolo = formato(unisci(baseTesto, nl?.optJSONObject("textStyle"), b.optJSONObject("textStyle")))
                if (!ps.has("indentStart")) nl?.optJSONObject("indentStart")?.let { inizio = ModelloDoc.pt(it) }
                if (!ps.has("indentFirstLine")) nl?.optJSONObject("indentFirstLine")?.let { primaRiga = ModelloDoc.pt(it) }
            }

            return ParagrafoDoc(
                inizio = e.optInt("startIndex", 0),
                fine = e.optInt("endIndex", 0),
                pezzi = pezzi,
                stile = nome,
                allineamento = base.optString("alignment").ifEmpty { "START" },
                rientroInizio = inizio,
                rientroPrimaRiga = primaRiga,
                sopra = ModelloDoc.pt(base.optJSONObject("spaceAbove")),
                sotto = ModelloDoc.pt(base.optJSONObject("spaceBelow")),
                interlinea = base.optDouble("lineSpacing", 100.0).takeIf { it > 0 } ?: 100.0,
                elenco = simbolo,
                formatoElenco = formatoSimbolo,
                interruzione = interruzione,
                posizionati = p.optJSONArray("positionedObjectIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                formatoBase = formato(baseTesto),
            )
        }

        /** Il simbolo o il numero di una voce di elenco. */
        private fun simboloElenco(lista: String, livello: Int, nl: JSONObject?): String {
            val c = contatori.getOrPut(lista) { IntArray(9) }
            val l = livello.coerceIn(0, 8)
            c[l]++
            for (k in l + 1 until 9) c[k] = 0
            val simbolo = nl?.optString("glyphSymbol").orEmpty()
            if (simbolo.isNotEmpty()) return simbolo
            val tipo = nl?.optString("glyphType").orEmpty()
            if (tipo.isEmpty() || tipo == "NONE" || tipo == "GLYPH_TYPE_UNSPECIFIED") return "•"
            val formato = nl?.optString("glyphFormat")?.takeIf { it.isNotEmpty() } ?: "%$l."
            val inizio = nl?.optInt("startNumber", 1) ?: 1
            return Regex("%(\\d)").replace(formato) { m ->
                val k = m.groupValues[1].toInt().coerceIn(0, 8)
                numero(c[k] + inizio - 1, if (k == l) tipo else tipo)
            }
        }

        private fun numero(n: Int, tipo: String): String = when (tipo) {
            "ALPHA" -> lettere(n).lowercase()
            "UPPER_ALPHA" -> lettere(n)
            "ROMAN" -> romano(n).lowercase()
            "UPPER_ROMAN" -> romano(n)
            "ZERO_DECIMAL" -> "%02d".format(n)
            else -> n.toString()
        }

        private fun lettere(n: Int): String {
            var x = n.coerceAtLeast(1)
            val sb = StringBuilder()
            while (x > 0) {
                x--
                sb.insert(0, 'A' + x % 26)
                x /= 26
            }
            return sb.toString()
        }

        private fun romano(n: Int): String {
            var x = n.coerceIn(1, 3999)
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
            return sb.toString()
        }

        private fun formato(t: JSONObject): FormatoDoc {
            val famiglia = t.optJSONObject("weightedFontFamily")
            val peso = famiglia?.optInt("weight", 400) ?: 400
            return FormatoDoc(
                grassetto = t.optBoolean("bold") || peso >= 600,
                corsivo = t.optBoolean("italic"),
                sottolineato = t.optBoolean("underline"),
                barrato = t.optBoolean("strikethrough"),
                colore = ModelloDoc.colore(t.optJSONObject("foregroundColor")),
                sfondo = ModelloDoc.colore(t.optJSONObject("backgroundColor")),
                punti = ModelloDoc.pt(t.optJSONObject("fontSize"), 11.0),
                famiglia = famiglia?.optString("fontFamily")?.takeIf { it.isNotEmpty() } ?: "Arial",
                spostamento = t.optString("baselineOffset").takeIf { it == "SUPERSCRIPT" || it == "SUBSCRIPT" },
                collegamento = t.has("link"),
            )
        }

        /** Le proprieta' di [livelli], dal piu' generale al piu' specifico: vince l'ultima. */
        private fun unisci(vararg livelli: JSONObject?): JSONObject {
            val out = JSONObject()
            for (l in livelli) if (l != null) for (k in l.keys()) out.put(k, l.get(k))
            return out
        }
    }
}
