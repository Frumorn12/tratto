package it.frumorn.tratto.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * L'indice (indice.json) e nota.json visti dal backup come JSON grezzo, con org.json.
 *
 * Il backup non passa dai modelli di Archivio: cosi' i campi che non conosce restano intatti
 * quando unisce due indici, e il codice gira anche nei test JVM senza android.util.JsonReader.
 */

/** Versioni massime che questa versione di Tratto sa leggere (le stesse che scrive Archivio). */
internal const val VERSIONE_INDICE = 1
internal const val VERSIONE_NOTA = 1

/** Id accettati per note e file nel backup: niente separatori, niente "..". Archivio.nuovoId() ne produce 16 esadecimali. */
internal val ID_VALIDO = Regex("[A-Za-z0-9_-]{1,64}")

internal fun indiceVuoto(): JSONObject =
    JSONObject().put("versione", VERSIONE_INDICE).put("cartelle", JSONArray()).put("note", JSONArray())

internal fun leggiIndiceDa(file: File): JSONObject = if (file.isFile) JSONObject(file.readText()) else indiceVuoto()

/** Gli oggetti di un array del JSON (le voci che non sono oggetti si ignorano). */
internal fun JSONObject.elenco(nome: String): List<JSONObject> {
    val a = optJSONArray(nome) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

/** Stringa o null: optString di Android restituisce "null" per i valori JSON null. */
internal fun JSONObject.testo(nome: String): String? = if (isNull(nome)) null else optString(nome)

internal val JSONObject.id: String get() = optString("id")
internal val JSONObject.modificata: Long get() = optLong("modificata")
internal val JSONObject.titolo: String get() = optString("titolo")

/** Copia di [base] (con gli eventuali campi sconosciuti) con cartelle e note sostituite. */
internal fun indiceCon(base: JSONObject, cartelle: List<JSONObject>, note: List<JSONObject>): JSONObject {
    val out = JSONObject(base.toString())
    out.put("cartelle", JSONArray().apply { cartelle.forEach { put(it) } })
    out.put("note", JSONArray().apply { note.forEach { put(it) } })
    return out
}

internal fun copiaJson(o: JSONObject): JSONObject = JSONObject(o.toString())
