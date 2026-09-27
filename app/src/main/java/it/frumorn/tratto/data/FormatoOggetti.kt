package it.frumorn.tratto.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Formato degli oggetti di una pagina (file pagine/<p>.oggetti.json accanto al .tp dei tratti).
 * L'ordine dell'elenco e' l'ordine di disegno: il primo sta sotto.
 *
 *   {"versione":1,"oggetti":[
 *     {"tipo":"immagine","id":"...","file":"immagini/<id>.webp","x":100,"y":200,"w":300,"h":200},
 *     {"tipo":"testo","id":"...","x":80,"y":120,"w":400,"paragrafi":[
 *        {"stile":"titolo","allineamento":"centro","elenco":"puntato","rientro":1,"frammenti":[
 *           {"testo":"Ciao","grassetto":true,"corsivo":true,"sottolineato":true,"barrato":true,
 *            "colore":"#E0354B","evidenziato":"#F2B705","punti":18,"carattere":"serif"}]}]}]}
 *
 * Coordinate in unita' di pagina (larga 1000), dimensioni del testo in punti tipografici, colori in
 * "#RRGGBB" (o "#AARRGGBB" se non opachi). Si scrivono solo i valori diversi dal predefinito; i campi
 * e i tipi sconosciuti si ignorano, cosi' una versione futura puo' aggiungerne senza rompere le vecchie.
 *
 * Usa org.json (quello di Android, o quello vero nei test JVM).
 */
object FormatoOggetti {
    const val VERSIONE = 1

    /** Nomi di file ammessi per le immagini: niente percorsi che escono dalla cartella della nota. */
    private val FILE_VALIDO = Regex("immagini/[A-Za-z0-9_-]{1,64}\\.(webp|png|jpg)")

    fun fileValido(file: String) = FILE_VALIDO.matches(file)

    fun scrivi(oggetti: List<Oggetto>): String {
        val elenco = JSONArray()
        for (o in oggetti) {
            val j = JSONObject().put("id", o.id)
            when (o) {
                is Immagine -> j.put("tipo", "immagine").put("file", o.file)
                    .put("x", num(o.x)).put("y", num(o.y)).put("w", num(o.larghezza)).put("h", num(o.altezza))
                is Testo -> j.put("tipo", "testo")
                    .put("x", num(o.x)).put("y", num(o.y)).put("w", num(o.larghezza))
                    .put("paragrafi", scriviTesto(o.contenuto))
            }
            elenco.put(j)
        }
        return JSONObject().put("versione", VERSIONE).put("oggetti", elenco).toString()
    }

    /** @throws IOException se il file non e' JSON o viene da una versione piu' recente */
    fun leggi(testo: String): MutableList<Oggetto> {
        val radice = try { JSONObject(testo) } catch (e: JSONException) { throw IOException("Oggetti della pagina illeggibili", e) }
        if (radice.optInt("versione", 1) > VERSIONE) throw IOException("Versione degli oggetti ${radice.optInt("versione")} non supportata")
        val out = ArrayList<Oggetto>()
        val elenco = radice.optJSONArray("oggetti") ?: return out
        for (i in 0 until elenco.length()) {
            val j = elenco.optJSONObject(i) ?: continue
            val id = j.optString("id")
            if (id.isEmpty()) continue
            val x = j.optDouble("x", 0.0).toFloat()
            val y = j.optDouble("y", 0.0).toFloat()
            val w = j.optDouble("w", 0.0).toFloat()
            when (j.optString("tipo")) {
                "immagine" -> {
                    val file = j.optString("file")
                    val h = j.optDouble("h", 0.0).toFloat()
                    if (!fileValido(file) || !(w > 0f) || !(h > 0f)) continue
                    out += Immagine(id, file, x, y, w, h)
                }
                "testo" -> {
                    val paragrafi = j.optJSONArray("paragrafi") ?: JSONArray()
                    out += Testo(id, x, y, w.coerceAtLeast(Testo.LARGHEZZA_MIN), leggiTesto(paragrafi))
                }
            }
        }
        return out
    }

    fun scriviTesto(t: TestoRicco): JSONArray {
        val out = JSONArray()
        for (p in t.paragrafi) {
            val jp = JSONObject()
            if (p.stile != StileParagrafo.NORMALE) jp.put("stile", p.stile.chiave)
            if (p.allineamento != Allineamento.SINISTRA) jp.put("allineamento", p.allineamento.chiave)
            if (p.elenco != Elenco.NESSUNO) jp.put("elenco", p.elenco.chiave)
            if (p.rientro > 0) jp.put("rientro", p.rientro)
            val fr = JSONArray()
            for (f in p.frammenti) {
                val jf = JSONObject().put("testo", f.testo)
                if (f.grassetto) jf.put("grassetto", true)
                if (f.corsivo) jf.put("corsivo", true)
                if (f.sottolineato) jf.put("sottolineato", true)
                if (f.barrato) jf.put("barrato", true)
                f.colore?.let { jf.put("colore", colore(it)) }
                f.evidenziato?.let { jf.put("evidenziato", colore(it)) }
                f.punti?.let { jf.put("punti", num(it)) }
                if (f.carattere != Carattere.MANROPE) jf.put("carattere", f.carattere.chiave)
                fr.put(jf)
            }
            jp.put("frammenti", fr)
            out.put(jp)
        }
        return out
    }

    fun leggiTesto(a: JSONArray): TestoRicco {
        val paragrafi = ArrayList<Paragrafo>()
        for (i in 0 until a.length()) {
            val jp = a.optJSONObject(i) ?: continue
            val frammenti = ArrayList<Frammento>()
            val fr = jp.optJSONArray("frammenti") ?: JSONArray()
            for (k in 0 until fr.length()) {
                val jf = fr.optJSONObject(k) ?: continue
                // Un "a capo" dentro un frammento spezzerebbe le posizioni dei paragrafi.
                val testo = jf.optString("testo").replace('\n', ' ').replace('\r', ' ')
                if (testo.isEmpty()) continue
                frammenti += Frammento(
                    testo = testo,
                    grassetto = jf.optBoolean("grassetto"),
                    corsivo = jf.optBoolean("corsivo"),
                    sottolineato = jf.optBoolean("sottolineato"),
                    barrato = jf.optBoolean("barrato"),
                    colore = leggiColore(jf.optString("colore")),
                    evidenziato = leggiColore(jf.optString("evidenziato")),
                    punti = jf.optDouble("punti", Double.NaN).takeIf { !it.isNaN() }?.let { TestoRicco.arrotondaPunti(it.toFloat()) },
                    carattere = Carattere.entries.find { it.chiave == jf.optString("carattere") } ?: Carattere.MANROPE,
                )
            }
            paragrafi += Paragrafo(
                frammenti = frammenti,
                stile = StileParagrafo.entries.find { it.chiave == jp.optString("stile") } ?: StileParagrafo.NORMALE,
                allineamento = Allineamento.entries.find { it.chiave == jp.optString("allineamento") } ?: Allineamento.SINISTRA,
                elenco = Elenco.entries.find { it.chiave == jp.optString("elenco") } ?: Elenco.NESSUNO,
                rientro = jp.optInt("rientro", 0).coerceIn(0, 8),
            )
        }
        return if (paragrafi.isEmpty()) TestoRicco.VUOTO else TestoRicco(paragrafi).normalizzato()
    }

    /** "#RRGGBB" se opaco, altrimenti "#AARRGGBB". */
    fun colore(argb: Int): String =
        if ((argb ushr 24) == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)

    fun leggiColore(s: String?): Int? {
        if (s == null || !s.startsWith("#")) return null
        val v = s.substring(1).toLongOrNull(16) ?: return null
        return when (s.length) {
            7 -> (0xFF000000L or v).toInt()
            9 -> v.toInt()
            else -> null
        }
    }

    /** Due decimali bastano (un centesimo di unita' e' meno di un ventesimo di millimetro) e il file resta piccolo. */
    private fun num(v: Float): Any {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.rint(r)) r.toLong() else r
    }
}
