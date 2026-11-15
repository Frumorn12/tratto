package it.frumorn.tratto.backup

import it.frumorn.tratto.data.Archive
import org.json.JSONObject

/** Una cartella di nota da portare nel nuovo archivio: dal backup o dall'archivio locale, con l'id di destinazione. */
internal class Operazione(val daBackup: Boolean, val origine: String, val destinazione: String)

internal class Piano(val indice: JSONObject, val operazioni: List<Operazione>, val riepilogo: RiepilogoRipristino)

internal fun titoloCopia(titolo: String) = "$titolo (copia)".trim()

/**
 * Regole di UNISCI (non si perde mai niente):
 *  - cartelle: restano quelle locali; si aggiungono quelle del backup che mancano (una cartella del
 *    backup con lo stesso nome di una locale diventa quella locale);
 *  - nota solo locale: resta; nota solo nel backup: si aggiunge;
 *  - nota da entrambe le parti con le stesse pagine: nessuna copia, si tengono i dati (titolo, cartella...)
 *    di quella con `modificata` piu' recente;
 *  - pagine diverse: la piu' recente tiene l'id, l'altra diventa una nuova nota "<titolo> (copia)".
 *    A parita' di data vince quella locale. Se la copia della versione del backup esiste gia'
 *    (stesso titolo e stesse pagine, per esempio perche' lo stesso backup e' stato ripristinato due volte)
 *    non se ne crea un'altra.
 *
 * [stessoContenuto] confronta la nota locale con quella del backup.
 */
internal fun pianificaUnione(
    locale: JSONObject,
    sorgente: LocalBackup.Sorgente,
    stessoContenuto: (idLocale: String, idBackup: String) -> Boolean,
    nuovoId: () -> String = { Archive.nuovoId() },
): Piano {
    val cartelle = locale.elenco("cartelle").map { copiaJson(it) }.toMutableList()
    val idCartelle = cartelle.mapTo(HashSet()) { it.id }
    val perId = cartelle.associateBy { it.id }
    val perNome = cartelle.filter { chiaveNome(it).isNotEmpty() }.associateBy { chiaveNome(it) }
    val rimappa = HashMap<String, String>()
    var cartelleAggiunte = 0
    for (c in sorgente.cartelle) {
        val gemella = perId[c.id] ?: perNome[chiaveNome(c)]?.takeIf { chiaveNome(c).isNotEmpty() }
        if (gemella != null) {
            if (gemella.id != c.id) rimappa[c.id] = gemella.id
            aggiungiCapitoli(gemella, c)
            continue
        }
        cartelle += c
        idCartelle += c.id
        cartelleAggiunte++
    }
    val capitoliDi = cartelle.associate { c -> c.id to c.elenco("capitoli").mapTo(HashSet()) { it.id } }

    /** Voce d'indice di una nota del backup, con la cartella tradotta (o tolta se non esiste). */
    fun daBackup(n: JSONObject, id: String = n.id, titolo: String = n.titolo): JSONObject {
        val v = copiaJson(n).put("id", id).put("titolo", titolo)
        val cartella = v.testo("cartella")?.let { rimappa[it] ?: it }
        if (cartella != null && cartella in idCartelle) v.put("cartella", cartella) else v.remove("cartella")
        val capitolo = v.testo("capitolo")
        if (capitolo == null || cartella == null || capitolo !in capitoliDi[cartella].orEmpty()) v.remove("capitolo")
        return v
    }

    val noteLocali = locale.elenco("note")
    val idLocali = noteLocali.mapTo(HashSet()) { it.id }
    val perIdBackup = sorgente.note.associateBy { it.id }
    val usati = HashSet(idLocali).apply { addAll(perIdBackup.keys) }
    fun idNuovo(): String {
        while (true) {
            val id = nuovoId()
            if (usati.add(id)) return id
        }
    }

    val note = ArrayList<JSONObject>()
    val op = ArrayList<Operazione>()
    var aggiunte = 0
    var aggiornate = 0
    var copie = 0
    for (l in noteLocali) {
        val id = l.id
        val valida = ID_VALIDO.matches(id)
        val b = perIdBackup[id]
        if (b == null || !valida) {
            note += l
            if (valida) op += Operazione(false, id, id)
            continue
        }
        val backupPiuNuova = b.modificata > l.modificata
        when {
            stessoContenuto(id, id) -> {
                if (backupPiuNuova) {
                    note += daBackup(b)
                    aggiornate++
                } else {
                    note += l
                }
                op += Operazione(false, id, id)
            }
            backupPiuNuova -> {
                note += daBackup(b)
                op += Operazione(true, id, id)
                aggiornate++
                val nuovo = idNuovo()
                note += copiaJson(l).put("id", nuovo).put("titolo", titoloCopia(l.titolo))
                op += Operazione(false, id, nuovo)
                copie++
            }
            else -> {
                note += l
                op += Operazione(false, id, id)
                val titolo = titoloCopia(b.titolo)
                val giaCopiata = noteLocali.any { it.titolo == titolo && ID_VALIDO.matches(it.id) && stessoContenuto(it.id, id) }
                if (!giaCopiata) {
                    val nuovo = idNuovo()
                    note += daBackup(b, nuovo, titolo)
                    op += Operazione(true, id, nuovo)
                    copie++
                }
            }
        }
    }
    for (b in sorgente.note) {
        if (b.id in idLocali) continue
        note += daBackup(b)
        op += Operazione(true, b.id, b.id)
        aggiunte++
    }
    return Piano(
        indiceCon(locale, cartelle, note),
        op,
        RiepilogoRipristino(aggiunte, aggiornate, copie, cartelleAggiunte, sorgente.saltate),
    )
}

private fun chiaveNome(c: JSONObject) = c.optString("nome").trim().lowercase()

/** I capitoli del quaderno del backup che il quaderno locale non ha ancora (stesso id) si aggiungono in fondo. */
private fun aggiungiCapitoli(locale: JSONObject, backup: JSONObject) {
    val nuovi = backup.elenco("capitoli").filter { it.id.isNotEmpty() }
    if (nuovi.isEmpty()) return
    val presenti = locale.elenco("capitoli").mapTo(HashSet()) { it.id }
    val elenco = locale.optJSONArray("capitoli") ?: org.json.JSONArray().also { locale.put("capitoli", it) }
    for (k in nuovi) if (presenti.add(k.id)) elenco.put(copiaJson(k))
}
