package it.frumorn.tratto.googledocs

import android.graphics.Bitmap
import android.graphics.RectF
import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.Impaginazione
import it.frumorn.tratto.pdf.EsportaPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** La nota collegata non esiste piu' nell'archivio: il collegamento si toglie, il documento resta. */
internal class NotaSparita : IOException("La nota non esiste più.")

/** Google Docs non e' riuscito a scaricare le immagini dei blocchi neanche riprovando: si riprova piu' tardi. */
internal class ImmagineNonScaricata(causa: Throwable) :
    IOException("Google Docs non è riuscito a scaricare le immagini: si riproverà.", causa)

/** Il documento cambiava di continuo mentre lo si aggiornava: si riprova piu' tardi. */
internal class DocumentoInMovimento : IOException("Il documento Google cambia mentre lo aggiorno: si riproverà.")

/** Un'immagine resa pubblica per il tempo di un batchUpdate: il file su Drive e il permesso da togliere. */
internal class Pubblicata(val id: String, val permesso: String)

/** Il lato Google di [SincroniaDocs] ([ServizioGoogle]; nei test un Google finto). */
internal interface ServizioDocs {
    /** documents.get con i campi di [DocLetto.CAMPI]. */
    suspend fun documento(id: String): JSONObject

    /** documents.batchUpdate; restituisce la risposta (con le repliche). Un rifiuto e' un [ErroreDrive]. */
    suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?): JSONObject

    /** Nome del file del documento su Drive. */
    suspend fun rinomina(id: String, nome: String)

    /** Mette il PNG su Drive, leggibile da chiunque abbia il link. */
    suspend fun pubblica(file: File, nome: String): Pubblicata

    /** Toglie la condivisione e cancella il file. */
    suspend fun ritira(immagine: Pubblicata)
}

/** Una pagina letta: i suoi dati e i blocchi in cui si divide. */
internal class PaginaLetta(val pagina: Pagina, val tratti: List<Tratto>, val oggetti: List<Oggetto>, val blocchi: List<BloccoPagina>)

/** Il lato Tratto di [SincroniaDocs]: la nota da mettere nel documento ([NotaArchivio]; nei test una nota finta). */
internal interface NotaDocs {
    val id: String

    /** Titolo e pagine, in ordine. [NotaSparita] se la nota non c'e' piu'. Fa I/O. */
    fun leggi(): Pair<NotaInfo, List<Pagina>>

    /** [FirmaPagina.firma] della pagina, o null se non si puo' fare. Fa I/O. */
    fun firma(pagina: Pagina): String?

    /** Legge tratti e oggetti della pagina e la divide in blocchi ([BlocchiPagina.dividi]). Fa I/O. */
    fun leggiPagina(pagina: Pagina): PaginaLetta

    /** Disegna la figura [blocco] di [letta] in PNG su [file] e restituisce lo SHA-256 del PNG. */
    suspend fun disegna(info: NotaInfo, letta: PaginaLetta, blocco: BloccoPagina, file: File): String
}

/** La nota vera, dall'archivio. */
internal class NotaArchivio(private val archivio: Archivio, override val id: String) : NotaDocs {
    override fun leggi(): Pair<NotaInfo, List<Pagina>> {
        archivio.caricaIndice()
        val info = archivio.note.value.find { it.id == id } ?: throw NotaSparita()
        return info to archivio.pagine(id)
    }

    /** I file sono quelli descritti in Archivio: se un giorno cambiano posto, la firma e' null e si rilegge sempre. */
    override fun firma(pagina: Pagina): String? {
        val cartella = File(archivio.cartellaNota(id), "pagine")
        return FirmaPagina.firma(File(cartella, "${pagina.id}.tp"), File(cartella, "${pagina.id}.oggetti.json"), pagina)
    }

    override fun leggiPagina(pagina: Pagina): PaginaLetta {
        val tratti = archivio.tratti(id, pagina.id)
        val oggetti = archivio.oggetti(id, pagina.id)
        return PaginaLetta(pagina, tratti, oggetti, BlocchiPagina.dividi(pagina, tratti, oggetti) { Impaginazione.impagina(it).altezza })
    }

    override suspend fun disegna(info: NotaInfo, letta: PaginaLetta, blocco: BloccoPagina, file: File): String = withContext(Dispatchers.IO) {
        val bmp = when (blocco) {
            is BloccoPagina.Intera -> EsportaPdf.immaginePagina(
                archivio, info, letta.pagina, (letta.pagina.larghezza * ContenutoDocs.PX_PER_UNITA).toInt(), letta.tratti, letta.oggetti,
            )
            is BloccoPagina.Disegno -> blocco.riquadro.let { r ->
                EsportaPdf.immagineRitaglio(archivio, info, RectF(r.sinistra, r.alto, r.destra, r.basso), blocco.tratti, blocco.immagini, ContenutoDocs.PX_PER_UNITA)
            }
            is BloccoPagina.Scritta -> error("Una casella di testo non si disegna: va nel documento come testo")
        }
        val dati = try {
            ByteArrayOutputStream(64 * 1024).also { out ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG non scritto")
            }.toByteArray()
        } finally {
            bmp.recycle()
        }
        file.parentFile?.mkdirs()
        file.writeBytes(dati)
        FirmaPagina.sha256(dati)
    }
}

/**
 * Porta un documento Google allo stato di una nota: il titolo, poi ogni pagina divisa in blocchi
 * ([BlocchiPagina]): le righe, le formule e i disegni come immagini trasparenti ritagliate, le
 * caselle di testo come testo vero. Si toccano solo i blocchi di Tratto (vedi [PianoBlocchi]):
 * quello che si aggiunge a mano nel documento resta.
 *
 * Si ricaricano solo i blocchi cambiati. Le pagine la cui firma non e' cambiata non si rileggono
 * nemmeno: i loro blocchi sono quelli salvati. Le immagini pubblicate per il batchUpdate si ritirano
 * subito dopo, qualunque cosa succeda.
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
     * Aggiorna il documento di [collegamento]. Le figure nuove si caricano a gruppi di
     * [LIMITE_IMMAGINI]; dopo ogni gruppo [progresso] riceve il collegamento aggiornato, da salvare.
     * Restituisce il collegamento finale.
     */
    suspend fun sincronizza(collegamento: CollegamentoDocs, progresso: suspend (CollegamentoDocs) -> Unit = {}): CollegamentoDocs {
        val (info, pagine) = withContext(Dispatchers.IO) { nota.leggi() }
        // Le firme si prendono prima di leggere: se un file cambia intanto, la prossima volta non coincide.
        val firme = withContext(Dispatchers.IO) {
            pagine.withIndex().associate { (i, p) -> p.id to nota.firma(p)?.let { "$it|${i == 0}" } }
        }
        val lette = HashMap<String, PaginaLetta>()
        val nome = nomeDocumento(info)

        var link = collegamento
        var rinominato = false
        var rifiuti = 0
        repeat(MAX_GIRI) {
            val d = DocLetto.da(servizio.documento(link.documento))
            if (!rinominato) {
                if (d.titolo != nome) servizio.rinomina(link.documento, nome)
                rinominato = true
            }

            // I blocchi voluti; se il piano ha bisogno di pagine non lette (testi da riscrivere,
            // figure da ridisegnare), si leggono e si rifa'.
            var voluti: List<BloccoVoluto>
            var piano: PianoBlocchi
            while (true) {
                voluti = voluti(info, pagine, firme, lette, link)
                piano = PianoBlocchi(d, link.blocchi, voluti, link.vecchio)
                val daLeggere = (piano.senzaContenuto.map { it.pagina } + piano.daDisegnare.map { it.pagina })
                    .filterNotNull().filter { it !in lette }.toSet()
                if (daLeggere.isEmpty()) break
                withContext(Dispatchers.IO) { for (p in pagine) if (p.id in daLeggere) lette[p.id] = nota.leggiPagina(p) }
            }

            // Un gruppo di figure alla volta: le altre restano come sono fino al giro dopo (quelle
            // gia' nel documento) o per ora non entrano (quelle nuove).
            val rinviate = HashSet<String>()
            while (piano.daDisegnare.size > LIMITE_IMMAGINI) {
                val oltre = piano.daDisegnare.drop(LIMITE_IMMAGINI).map { it.chiave }.toSet()
                voluti = if (rinviate.addAll(oltre)) rinvia(voluti, rinviate, link) else voluti.filter { it.chiave !in oltre }
                piano = PianoBlocchi(d, link.blocchi, voluti, link.vecchio)
            }

            val disegni = LinkedHashMap<String, Disegno>()
            val pubblicate = ConcurrentHashMap<String, Pubblicata>()
            try {
                // Disegno delle figure; quelle rimaste al loro posto con un disegno identico non si ricaricano.
                val salvati = link.blocchi.associateBy { it.chiave }
                val tenute = piano.tenute
                val identiche = HashSet<String>()
                for (f in piano.daDisegnare) {
                    val letta = lette.getValue(f.pagina!!)
                    val blocco = letta.blocchi.first { chiave(letta.pagina, it) == f.chiave }
                    val file = File(cartellaLocale, "${nota.id}-${f.chiave.replace('/', '-')}.png")
                    val png = nota.disegna(info, letta, blocco, file)
                    val s = salvati[f.chiave]
                    if (f.chiave in tenute && s != null && s.figura && s.png == png && s.larghezzaPt == f.larghezzaPt && s.altezzaPt == f.altezzaPt) {
                        identiche += f.chiave
                        file.delete()
                    } else {
                        disegni[f.chiave] = Disegno(file, png)
                    }
                }
                if (identiche.isNotEmpty()) piano = PianoBlocchi(d, link.blocchi, voluti, link.vecchio, identiche)

                if (piano.vuoto) {
                    link = chiudiGiro(link, piano.stato(null, null, emptyMap()), pagine, firme, lette, rinviate)
                    progresso(link)
                    if (rinviate.isEmpty()) return link
                    return@repeat
                }

                // Solo le figure che il piano mette davvero (le identiche no).
                val servono = piano.daDisegnare.map { it.chiave }.toSet()
                coroutineScope {
                    val permessi = Semaphore(PUBBLICAZIONI_INSIEME)
                    servono.map { k ->
                        async { permessi.withPermit { pubblicate[k] = servizio.pubblica(disegni.getValue(k).file, "${nota.id}-${k.replace('/', '-')}.png") } }
                    }.awaitAll()
                }
                val (richieste, risposta) = try {
                    aggiornaDocumento(link.documento, piano, HashMap(pubblicate), d.revisione)
                } catch (e: ErroreDrive) {
                    // 400: il documento e' cambiato dopo averlo letto (requiredRevisionId), oppure Docs
                    // rifiuta le richieste. Si rilegge il documento e si rifa' il piano.
                    if (e.codice != 400 || ++rifiuti > MAX_RIFIUTI) throw e
                    return@repeat
                }
                val nuovi = piano.stato(richieste, risposta, disegni.mapValues { it.value.png })
                link = chiudiGiro(link, nuovi, pagine, firme, lette, rinviate)
                progresso(link)
                if (rinviate.isEmpty()) return link
            } finally {
                withContext(NonCancellable) {
                    ritira(pubblicate.values.toList())
                    disegni.values.forEach { it.file.delete() }
                }
            }
        }
        throw DocumentoInMovimento()
    }

    /** I blocchi voluti della nota: il titolo, poi le pagine (dai dati letti, o i blocchi salvati se la pagina non e' cambiata). */
    private suspend fun voluti(
        info: NotaInfo,
        pagine: List<Pagina>,
        firme: Map<String, String?>,
        lette: MutableMap<String, PaginaLetta>,
        link: CollegamentoDocs,
    ): List<BloccoVoluto> {
        val out = ArrayList<BloccoVoluto>()
        out += ContenutoDocs.titolo(EsportaPdf.titolo(info))
        val salvatiPerPagina = link.blocchi.groupBy { it.pagina }
        for ((i, p) in pagine.withIndex()) {
            val letta = lette[p.id]
            val firma = firme[p.id]
            if (letta == null && !link.vecchio && firma != null && link.firme[p.id] == firma) {
                salvatiPerPagina[p.id].orEmpty().mapTo(out) { daSalvato(it) }
                continue
            }
            out += daPagina(letta ?: withContext(Dispatchers.IO) { nota.leggiPagina(p) }.also { lette[p.id] = it }, i == 0)
        }
        return out
    }

    private fun daPagina(letta: PaginaLetta, prima: Boolean): List<BloccoVoluto> {
        val aspetti = ContenutoDocs.aspetti(letta.blocchi, prima)
        return letta.blocchi.mapIndexed { i, b ->
            val chiave = chiave(letta.pagina, b)
            val impronta = BlocchiPagina.impronta(letta.pagina, b, letta.tratti, letta.oggetti)
            when (b) {
                is BloccoPagina.Scritta -> BloccoVoluto.Scritto(chiave, letta.pagina.id, impronta, aspetti[i], ContenutoDocs.paragrafi(b.testo.contenuto))
                else -> {
                    val (w, h) = ContenutoDocs.misure(b)
                    BloccoVoluto.Figura(chiave, letta.pagina.id, impronta, aspetti[i], w, h)
                }
            }
        }
    }

    /** Le figure [rinviate] restano come sono nel documento (quelle nuove per ora non entrano). */
    private fun rinvia(voluti: List<BloccoVoluto>, rinviate: Set<String>, link: CollegamentoDocs): List<BloccoVoluto> {
        val salvati = link.blocchi.associateBy { it.chiave }
        return voluti.mapNotNull { v ->
            if (v.chiave !in rinviate || v !is BloccoVoluto.Figura) return@mapNotNull v
            val s = salvati[v.chiave]?.takeIf { it.figura } ?: return@mapNotNull null
            v.copy(impronta = s.impronta, larghezzaPt = s.larghezzaPt, altezzaPt = s.altezzaPt)
        }
    }

    /**
     * Il collegamento dopo un giro: i blocchi nuovi e le firme delle pagine lette, tranne quelle con
     * figure rinviate (la prossima volta si rileggono).
     */
    private fun chiudiGiro(
        link: CollegamentoDocs,
        blocchi: List<BloccoSalvato>,
        pagine: List<Pagina>,
        firme: Map<String, String?>,
        lette: Map<String, PaginaLetta>,
        rinviate: Set<String>,
    ): CollegamentoDocs {
        val conRinvii = rinviate.map { it.substringBefore('/') }.toSet()
        val nuove = LinkedHashMap<String, String>()
        for (p in pagine) {
            val f = if (p.id in lette) firme[p.id] else link.firme[p.id]
            if (f != null && p.id !in conRinvii) nuove[p.id] = f
        }
        return link.copy(blocchi = blocchi, firme = nuove, vecchio = false)
    }

    /** batchUpdate; se Google non riesce a scaricare le immagini si aspetta e si prova l'altro indirizzo. */
    private suspend fun aggiornaDocumento(
        documento: String,
        piano: PianoBlocchi,
        pubblicate: Map<String, Pubblicata>,
        revisione: String?,
    ): Pair<PianoBlocchi.Richieste, JSONObject> {
        var tentativo = 0
        while (true) {
            val richieste = piano.richieste(pubblicate.mapValues { indirizzoImmagine(it.value.id, tentativo) })
            try {
                return richieste to servizio.aggiorna(documento, richieste.json, revisione)
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

    private class Disegno(val file: File, val png: String)

    companion object {
        /** Figure nuove per ogni batchUpdate: una nota lunga appena collegata entra a gruppi. */
        const val LIMITE_IMMAGINI = 20

        /** Immagini caricate su Drive insieme. */
        private const val PUBBLICAZIONI_INSIEME = 4

        private const val TENTATIVI_IMMAGINI = 3
        private const val MAX_RIFIUTI = 3
        private const val TEMPO_PULIZIA_MS = 30_000L

        /** Giri al massimo: i gruppi di una nota lunghissima, piu' qualcuno per i documenti cambiati nel frattempo. */
        private const val MAX_GIRI = 60

        fun nomeDocumento(info: NotaInfo): String =
            ContenutoDocs.pulisci(EsportaPdf.titolo(info)).trim().take(200).ifBlank { EsportaPdf.TITOLO_PREDEFINITO }

        /** La chiave di un blocco nella nota: pagina e blocco. */
        fun chiave(pagina: Pagina, b: BloccoPagina) = "${pagina.id}/${b.chiave}"

        private fun daSalvato(s: BloccoSalvato): BloccoVoluto {
            val a = s.aspetto.split(',').mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) Aspetto(it[0], it[1]) else Aspetto.NESSUNO }
            return if (s.figura) BloccoVoluto.Figura(s.chiave, s.pagina, s.impronta, a, s.larghezzaPt, s.altezzaPt)
            else BloccoVoluto.Scritto(s.chiave, s.pagina, s.impronta, a, null)
        }

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
    }
}
