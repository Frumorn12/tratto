package it.frumorn.tratto.googledocs

import android.graphics.Bitmap
import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.pdf.EsportaPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** La nota collegata non esiste piu' nell'archivio: il collegamento si toglie, il documento resta. */
internal class NotaSparita : IOException("La nota non esiste più.")

/** Google Docs non e' riuscito a scaricare le immagini delle pagine neanche riprovando: si riprova piu' tardi. */
internal class ImmagineNonScaricata(causa: Throwable) :
    IOException("Google Docs non è riuscito a scaricare le immagini delle pagine: si riproverà.", causa)

/** Il documento cambiava di continuo mentre lo si aggiornava: si riprova piu' tardi. */
internal class DocumentoInMovimento : IOException("Il documento Google cambia mentre lo aggiorno: si riproverà.")

/** Un'immagine resa pubblica per il tempo di un batchUpdate: il file su Drive e il permesso da togliere. */
internal class Pubblicata(val id: String, val permesso: String)

/** Il lato Google di [SincroniaDocs] ([ServizioGoogle]; nei test un Google finto). */
internal interface ServizioDocs {
    /** documents.get con i campi di [LetturaDoc.CAMPI]. */
    suspend fun documento(id: String): JSONObject

    /** documents.batchUpdate; un rifiuto e' un [ErroreDrive]. */
    suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?)

    /** Nome del file del documento su Drive. */
    suspend fun rinomina(id: String, nome: String)

    /** Mette il PNG su Drive, leggibile da chiunque abbia il link. */
    suspend fun pubblica(file: File, nome: String): Pubblicata

    /** Toglie la condivisione e cancella il file. */
    suspend fun ritira(immagine: Pubblicata)
}

/** Il lato Tratto di [SincroniaDocs]: la nota da mettere nel documento ([NotaArchivio]; nei test una nota finta). */
internal interface NotaDocs {
    val id: String

    /** Titolo e pagine, in ordine. [NotaSparita] se la nota non c'e' piu'. Fa I/O. */
    fun leggi(): Pair<NotaInfo, List<Pagina>>

    /** [ImprontaPagina.firma] della pagina, o null se non si puo' fare. Fa I/O. */
    fun firma(pagina: Pagina, testo: List<String>): String?

    /** I tratti salvati della pagina. Fa I/O. */
    fun tratti(pagina: Pagina): List<Tratto>

    /** Disegna la pagina in PNG su [file] e restituisce lo SHA-256 del PNG. */
    suspend fun disegna(info: NotaInfo, pagina: Pagina, tratti: List<Tratto>, file: File): String

    /**
     * Punto di aggancio per il testo battuto della pagina: un elemento per paragrafo, messo come
     * testo vero sotto la sua immagine (ed entra nell'impronta, cosi' l'immagine si rifa' se il testo
     * e' disegnato anche li'). Per ora nessun testo: vedi [NotaArchivio].
     */
    fun testo(pagina: Pagina): List<String> = emptyList()
}

/**
 * La nota vera, dall'archivio. [testoPagina] e' il punto di aggancio per le caselle di testo: quando
 * le pagine le avranno, basta passare qui (in GoogleDocs) la funzione che ne restituisce il testo
 * semplice, un elemento per paragrafo.
 */
internal class NotaArchivio(
    private val archivio: Archivio,
    override val id: String,
    private val testoPagina: (notaId: String, pagina: Pagina) -> List<String> = { _, _ -> emptyList() },
) : NotaDocs {
    override fun leggi(): Pair<NotaInfo, List<Pagina>> {
        archivio.caricaIndice()
        val info = archivio.note.value.find { it.id == id } ?: throw NotaSparita()
        return info to archivio.pagine(id)
    }

    /** Il file dei tratti e' quello descritto in Archivio: se un giorno cambia posto, la firma e' null e si rilegge sempre. */
    override fun firma(pagina: Pagina, testo: List<String>): String? {
        val base = ImprontaPagina.firma(File(archivio.cartellaNota(id), "pagine/${pagina.id}.tp"), pagina, testo) ?: return null
        // Immagini e caselle di testo stanno accanto ai tratti: se cambiano, la pagina va ridisegnata.
        val oggetti = File(archivio.cartellaNota(id), "pagine/${pagina.id}.oggetti.json")
        return if (oggetti.exists()) "$base|${oggetti.lastModified()}:${oggetti.length()}" else base
    }

    override fun tratti(pagina: Pagina): List<Tratto> = archivio.tratti(id, pagina.id)

    override suspend fun disegna(info: NotaInfo, pagina: Pagina, tratti: List<Tratto>, file: File): String = withContext(Dispatchers.IO) {
        val bmp = EsportaPdf.immaginePagina(archivio, info, pagina, SincroniaDocs.LARGHEZZA_PX, tratti)
        val dati = try {
            ByteArrayOutputStream(256 * 1024).also { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG non scritto")
            }.toByteArray()
        } finally {
            bmp.recycle()
        }
        file.parentFile?.mkdirs()
        file.writeBytes(dati)
        ImprontaPagina.sha256(dati)
    }

    override fun testo(pagina: Pagina): List<String> = testoPagina(id, pagina)
}

/**
 * Porta un documento Google allo stato di una nota: il titolo come titolo del documento, poi ogni
 * pagina come immagine in linea, in ordine, con sotto l'eventuale testo della pagina.
 *
 * Si ricaricano solo le pagine cambiate: per ogni pagina il collegamento ricorda l'[ImprontaPagina]
 * dei dati disegnati, lo SHA-256 del PNG caricato e una firma economica (vedi [PaginaDocs]).
 * Le immagini pubblicate per il batchUpdate si ritirano subito dopo, qualunque cosa succeda.
 */
internal class SincroniaDocs(
    private val servizio: ServizioDocs,
    private val nota: NotaDocs,
    /** Cartella locale per i PNG da caricare (nella cache). */
    private val cartellaLocale: File,
    /** Attesa tra un tentativo e l'altro (nei test non si aspetta). */
    private val aspetta: suspend (Long) -> Unit = { delay(it) },
) {
    /** Vero se qualche immagine pubblicata non si e' potuta ritirare (la togliera' la pulizia degli avanzi). */
    var avanzi = false
        private set

    /**
     * Aggiorna il documento di [collegamento]. Le immagini nuove si caricano a gruppi di
     * [LIMITE_IMMAGINI]; dopo ogni gruppo [progresso] riceve il collegamento aggiornato, da salvare.
     * Restituisce il collegamento finale.
     */
    suspend fun sincronizza(collegamento: CollegamentoDocs, progresso: suspend (CollegamentoDocs) -> Unit = {}): CollegamentoDocs {
        val (info, pagine) = withContext(Dispatchers.IO) { nota.leggi() }
        val testi = pagine.associate { p ->
            p.id to runCatching { nota.testo(p) }.getOrDefault(emptyList()).flatMap { it.split('\n') }.map(::pulisciTesto)
        }
        val voluti = ArrayList<Voluto>()
        voluti += Voluto.Testo(pulisciTesto(EsportaPdf.titolo(info)), StileParagrafo.TITOLO)
        for (p in pagine) {
            voluti += Voluto.immagine(p.id, p.larghezza, p.altezza)
            testi.getValue(p.id).forEach { voluti += Voluto.Testo(it) }
        }
        val perId = pagine.associateBy { it.id }
        val nome = nomeDocumento(info)

        var link = collegamento
        var doc: JSONObject? = null
        var rinominato = false
        var rifiuti = 0
        var ricostruisci = false
        // Un giro per gruppo di immagini, piu' qualcuno per i documenti cambiati nel frattempo.
        repeat(pagine.size / LIMITE_IMMAGINI + 5) {
            val d = doc ?: servizio.documento(link.documento)
            doc = null
            if (!rinominato) {
                if (d.optString("title") != nome) servizio.rinomina(link.documento, nome)
                rinominato = true
            }
            val attuali = if (ricostruisci) corpoIntero(d) else LetturaDoc.paragrafi(d, link.immagini())
            val completo = PianoDocs(attuali, voluti)

            // Pagine da ridisegnare: quelle da inserire e quelle rimaste i cui dati sono cambiati.
            // [verificate]: pagine rimaste con l'immagine ancora giusta, di cui ricordare impronta e firma nuove.
            val verificate = HashMap<String, Verifica>()
            val candidate = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (p in pagine) {
                    val salvata = link.pagine[p.id]
                    if (p.id !in completo.tenute || salvata == null) {
                        candidate += p.id
                        continue
                    }
                    // La firma prima dei tratti: se il file cambia intanto, la prossima volta non coincide.
                    val firma = nota.firma(p, testi.getValue(p.id))
                    if (firma != null && firma == salvata.firma) continue
                    val impronta = ImprontaPagina.di(p, nota.tratti(p), testi.getValue(p.id))
                    if (impronta == salvata.impronta) verificate[p.id] = Verifica(impronta, firma) else candidate += p.id
                }
            }

            // Un gruppo alla volta. Le pagine nuove rimaste fuori per ora non entrano nel documento;
            // se il piano ridotto ne vuole comunque qualcuna (ordine cambiato), entra nel gruppo.
            val gruppo = LinkedHashSet(candidate.take(LIMITE_IMMAGINI))
            var piano: PianoDocs
            var volutiGiro: List<Voluto>
            while (true) {
                val escluse = candidate.filter { it !in gruppo && it !in completo.tenute }.toSet()
                volutiGiro = if (escluse.isEmpty()) voluti else volutiSenza(voluti, escluse)
                piano = PianoDocs(attuali, volutiGiro)
                val mancanti = piano.daInserire.map { it.pagina }.filter { it !in gruppo }
                if (mancanti.isEmpty()) break
                gruppo += mancanti
            }
            val ultimoGruppo = candidate.all { it in gruppo }

            // Disegno delle pagine del gruppo; quelle rimaste con un disegno identico non si caricano.
            val disegni = LinkedHashMap<String, Disegno>()
            val pubblicate = LinkedHashMap<String, Pubblicata>()
            try {
                for (id in gruppo) {
                    val p = perId.getValue(id)
                    val testo = testi.getValue(id)
                    val (firma, tratti) = withContext(Dispatchers.IO) { nota.firma(p, testo) to nota.tratti(p) }
                    val verifica = Verifica(ImprontaPagina.di(p, tratti, testo), firma)
                    val file = File(cartellaLocale, "${nota.id}-$id.png")
                    val png = nota.disegna(info, p, tratti, file)
                    val salvata = link.pagine[id]
                    if (id in piano.tenute && salvata != null && salvata.png == png) {
                        verificate[id] = verifica
                        file.delete()
                        continue
                    }
                    disegni[id] = Disegno(file, png, verifica)
                }

                if (piano.strutturaUguale && disegni.isEmpty()) {
                    // Il documento e' gia' giusto: si ricordano solo impronte e firme controllate.
                    link = link.copy(pagine = link.pagine.mapValues { (id, v) -> verificate[id]?.let { v.conVerifica(it) } ?: v })
                    if (ultimoGruppo) return link
                    progresso(link)
                    doc = d
                    return@repeat
                }

                try {
                    for ((id, disegno) in disegni) pubblicate[id] = servizio.pubblica(disegno.file, "${nota.id}-$id.png")
                    aggiornaDocumento(link.documento, piano, pubblicate, d.optString("revisionId").takeIf { it.isNotEmpty() })
                } catch (e: ErroreDrive) {
                    // 400: il documento e' cambiato dopo averlo letto (requiredRevisionId), oppure Docs
                    // rifiuta le richieste ("Invalid requests[n]..."). Si rilegge il documento; se le
                    // richieste vengono rifiutate di nuovo si svuota il corpo e si riscrive tutto.
                    if (e.codice != 400 || ++rifiuti > 3) throw e
                    ricostruisci = rifiuti >= 2 && e.dettaglio?.contains("Invalid requests") == true
                    return@repeat
                }
                ricostruisci = false

                // Gli id delle immagini dopo le modifiche, nell'ordine delle pagine.
                val dopo = servizio.documento(link.documento)
                val ids = LetturaDoc.immagini(dopo)
                val volute = volutiGiro.filterIsInstance<Voluto.Immagine>()
                val nuove = LinkedHashMap<String, PaginaDocs>()
                val riconosciuto = ids.size == volute.size
                if (riconosciuto) {
                    for ((k, v) in volute.withIndex()) {
                        val disegno = disegni[v.pagina]
                        val prima = link.pagine[v.pagina]
                        nuove[v.pagina] = when {
                            disegno != null -> PaginaDocs(ids[k], v.dimensione, disegno.verifica.impronta, disegno.png, disegno.verifica.firma ?: "")
                            // Immagine rimasta: impronta nuova se controllata, altrimenti la vecchia (da rifare).
                            prima != null -> (verificate[v.pagina]?.let { prima.conVerifica(it) } ?: prima).copy(oggetto = ids[k])
                            else -> continue
                        }
                    }
                } else {
                    // Qualcuno ha cambiato il documento proprio adesso: si tiene quello che si riconosce ancora.
                    for ((id, v) in link.pagine) if (v.oggetto in ids && id !in disegni) nuove[id] = v
                }
                link = link.copy(pagine = nuove)
                progresso(link)
                if (ultimoGruppo && riconosciuto) return link
                doc = dopo
            } finally {
                withContext(NonCancellable) {
                    ritira(pubblicate.values)
                    disegni.values.forEach { it.file.delete() }
                }
            }
        }
        throw DocumentoInMovimento()
    }

    /** Le richieste nel documento; se Google non riesce a scaricare le immagini si aspetta e si prova l'altro indirizzo. */
    private suspend fun aggiornaDocumento(documento: String, piano: PianoDocs, pubblicate: Map<String, Pubblicata>, revisione: String?) {
        var tentativo = 0
        while (true) {
            val uri = pubblicate.mapValues { indirizzoImmagine(it.value.id, tentativo) }
            try {
                servizio.aggiorna(documento, piano.richieste(uri), revisione)
                return
            } catch (e: ErroreDrive) {
                if (e.codice != 400 || !immagineNonScaricata(e.dettaglio)) throw e
                if (++tentativo >= TENTATIVI_IMMAGINI) throw ImmagineNonScaricata(e)
                // Il permesso pubblico appena dato a volte non e' ancora arrivato dappertutto.
                aspetta(1500L * tentativo)
            }
        }
    }

    /** Ritira le immagini pubblicate; quelle che non si riesce a togliere restano per la pulizia degli avanzi. */
    private suspend fun ritira(pubblicate: Collection<Pubblicata>) {
        if (pubblicate.isEmpty()) return
        val finito = withTimeoutOrNull(TEMPO_PULIZIA_MS) {
            for (p in pubblicate) runCatching { servizio.ritira(p) }.onFailure { avanzi = true }
        }
        if (finito == null) avanzi = true
    }

    /** Un solo paragrafo finto che copre tutto il corpo: il piano lo cancella e riscrive tutto. */
    private fun corpoIntero(doc: JSONObject): List<ParagrafoDoc> {
        val fine = LetturaDoc.paragrafi(doc, emptyMap()).lastOrNull()?.fine ?: 2
        return listOf(ParagrafoDoc(1, fine, "X\u0000tutto"))
    }

    /** Impronta dei dati di una pagina e la firma presa prima di leggerli. */
    private class Verifica(val impronta: String, val firma: String?)

    private fun PaginaDocs.conVerifica(v: Verifica) = copy(impronta = v.impronta, firma = v.firma ?: "")

    private class Disegno(val file: File, val png: String, val verifica: Verifica)

    companion object {
        /** Larghezza delle immagini delle pagine: nitide anche ingrandite nel documento (circa 260 dpi). */
        const val LARGHEZZA_PX = 1600

        /** Immagini nuove per ogni batchUpdate: una nota lunga appena collegata entra a gruppi. */
        const val LIMITE_IMMAGINI = 10

        private const val TENTATIVI_IMMAGINI = 3
        private const val TEMPO_PULIZIA_MS = 30_000L

        fun nomeDocumento(info: NotaInfo): String =
            pulisciTesto(EsportaPdf.titolo(info)).take(200).ifBlank { EsportaPdf.TITOLO_PREDEFINITO }

        /**
         * Indirizzo pubblico di un file di Drive che Google Docs accetta. Prima lh3.googleusercontent.com
         * (risponde subito con l'immagine, senza redirect), poi uc?export=download (redirect a
         * drive.usercontent.google.com), che Docs a volte rifiuta.
         */
        fun indirizzoImmagine(id: String, tentativo: Int): String =
            if (tentativo % 2 == 0) "https://lh3.googleusercontent.com/d/$id" else "https://drive.google.com/uc?export=download&id=$id"

        /** Il 400 di batchUpdate dice che Google non ha potuto prendere l'immagine, non che le richieste sono sbagliate. */
        fun immagineNonScaricata(dettaglio: String?): Boolean {
            val d = dettaglio?.lowercase() ?: return false
            return "image" in d && ("retriev" in d || "forbidden" in d || "publicly accessible" in d)
        }

        /** Tutti i voluti tranne le immagini (e il testo sotto) delle pagine [escluse]. */
        fun volutiSenza(voluti: List<Voluto>, escluse: Set<String>): List<Voluto> {
            val out = ArrayList<Voluto>()
            var salta = false
            for (v in voluti) {
                if (v is Voluto.Immagine) salta = v.pagina in escluse
                else if (v.stile == StileParagrafo.TITOLO) salta = false
                if (!salta) out += v
            }
            return out
        }
    }
}
