package it.frumorn.tratto.backup

import it.frumorn.tratto.data.Archivio
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
    sorgente: BackupLocale.Sorgente,
    stessoContenuto: (idLocale: String, idBackup: String) -> Boolean,
    nuovoId: () -> String = { Archivio.nuovoId() },
): Piano {
    val cartelle = locale.elenco("cartelle").toMutableList()
    val idCartelle = cartelle.mapTo(HashSet()) { it.id }
    val perNome = cartelle.filter { chiaveNome(it).isNotEmpty() }.associateBy { chiaveNome(it) }
    val rimappa = HashMap<String, String>()
    var cartelleAggiunte = 0
    for (c in sorgente.cartelle) {
        if (c.id in idCartelle) continue
        val omonima = perNome[chiaveNome(c)]
        if (omonima != null && chiaveNome(c).isNotEmpty()) {
            rimappa[c.id] = omonima.id
            continue
        }
        cartelle += c
        idCartelle += c.id
        cartelleAggiunte++
    }

    /** Voce d'indice di una nota del backup, con la cartella tradotta (o tolta se non esiste). */
    fun daBackup(n: JSONObject, id: String = n.id, titolo: String = n.titolo): JSONObject {
        val v = copiaJson(n).put("id", id).put("titolo", titolo)
        val cartella = v.testo("cartella")?.let { rimappa[it] ?: it }
        if (cartella != null && cartella in idCartelle) v.put("cartella", cartella) else v.remove("cartella")
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
