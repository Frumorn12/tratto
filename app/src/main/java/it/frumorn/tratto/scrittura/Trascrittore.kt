package it.frumorn.tratto.scrittura

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Trascrizione della scrittura a mano con ML Kit Digital Ink Recognition, tutta sul dispositivo.
 *
 * Il modello italiano ("it", circa 14 MB da scaricare) arriva da Google al primo uso con
 * [scaricaModello]; dopo si lavora senza rete. ML Kit non si inizializza piu' all'avvio dell'app
 * (il suo ContentProvider e' tolto nel manifest): lo si fa qui, al primo uso, fuori dal main thread.
 */
object Trascrittore {
    const val LINGUA = "it"

    /** Stato del modello di riconoscimento. ML Kit non da' la percentuale del download. */
    sealed interface StatoModello {
        data object Assente : StatoModello
        data object InDownload : StatoModello
        data object Pronto : StatoModello
        data class Errore(val messaggio: String) : StatoModello
    }

    private val _stato = MutableStateFlow<StatoModello>(StatoModello.Assente)
    val stato: StateFlow<StatoModello> = _stato.asStateFlow()

    /** Il download continua anche se chi l'ha chiesto esce dalla schermata. */
    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scaricamento: Deferred<Boolean>? = null
    private val mutex = Mutex()

    @Volatile private var inizializzato = false
    private var riconoscitore: DigitalInkRecognizer? = null

    private val modello: DigitalInkRecognitionModel by lazy {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LINGUA) ?: DigitalInkRecognitionModelIdentifier.IT
        DigitalInkRecognitionModel.builder(id).build()
    }

    private suspend fun inizializza(context: Context) {
        if (inizializzato) return
        withContext(Dispatchers.IO) {
            synchronized(this@Trascrittore) {
                if (!inizializzato) {
                    MlKit.initialize(context.applicationContext)
                    inizializzato = true
                }
            }
        }
    }

    /** Vero se il modello italiano e' gia' sul dispositivo. Aggiorna [stato]. */
    suspend fun modelloPronto(context: Context): Boolean {
        inizializza(context)
        val pronto = RemoteModelManager.getInstance().isModelDownloaded(modello).attendi() == true
        _stato.value = when {
            pronto -> StatoModello.Pronto
            _stato.value == StatoModello.Pronto -> StatoModello.Assente
            else -> _stato.value
        }
        return pronto
    }

    /**
     * Scarica il modello italiano se manca e aspetta la fine; con [soloWifi] il download parte solo
     * sotto Wi-Fi (fino ad allora resta in attesa). Gli errori finiscono in [stato] come
     * [StatoModello.Errore]; restituisce vero se alla fine il modello e' pronto.
     */
    suspend fun scaricaModello(context: Context, soloWifi: Boolean = false): Boolean {
        inizializza(context)
        val lavoro = synchronized(this) {
            scaricamento?.takeIf { it.isActive } ?: ambito.async { scarica(soloWifi) }.also { scaricamento = it }
        }
        return lavoro.await()
    }

    private suspend fun scarica(soloWifi: Boolean): Boolean {
        val gestore = RemoteModelManager.getInstance()
        return try {
            if (gestore.isModelDownloaded(modello).attendi() == true) {
                _stato.value = StatoModello.Pronto
                return true
            }
            _stato.value = StatoModello.InDownload
            val condizioni = DownloadConditions.Builder().apply { if (soloWifi) requireWifi() }.build()
            gestore.download(modello, condizioni).attendi()
            _stato.value = StatoModello.Pronto
            true
        } catch (_: CancellationException) {
            // Solo se ML Kit annulla il download: questo ambito non viene mai cancellato.
            _stato.value = StatoModello.Assente
            false
        } catch (e: Exception) {
            _stato.value = StatoModello.Errore(e.message?.let { "Download del modello non riuscito: $it" } ?: "Download del modello non riuscito")
            false
        }
    }

    /**
     * Trascrive [tratti] (unita' di pagina) e restituisce il testo del candidato migliore, con le
     * righe separate da "\n". Le righe si riconoscono una alla volta, ognuna con il testo della
     * precedente come contesto. I tratti dell'evidenziatore si ignorano.
     *
     * @throws IllegalStateException se il modello non e' stato scaricato (vedi [scaricaModello]).
     */
    suspend fun trascrivi(context: Context, tratti: List<Tratto>): String {
        val scritti = tratti.filter { it.penna != Penna.EVIDENZIATORE && it.quanti > 0 }
        if (scritti.isEmpty()) return ""
        inizializza(context)
        return mutex.withLock {
            if (!modelloPronto(context)) throw IllegalStateException("Il modello per la scrittura in italiano non e' ancora scaricato")
            val client = riconoscitore ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(modello).build())
                .also { riconoscitore = it }
            val righe = Righe.dividi(scritti)
            // L'area di scrittura dice al modello quanto e' grande la scrittura rispetto alla riga
            // (serve a distinguere "o" da "O"): si usa la distanza tipica tra le righe, o con una
            // riga sola tre volte l'altezza delle minuscole, come su un quaderno a righe.
            val interlinea = if (righe.size > 1) {
                Righe.mediana(righe.zipWithNext { a, b -> b.centro - a.centro })
            } else 3f * BellaScrittura.altezzaXStimata(scritti)
            val testi = ArrayList<String>()
            var precedente = ""
            for (riga in righe) {
                val altezza = max(riga.altezza, interlinea)
                val contesto = RecognitionContext.builder()
                    // Il testo prima dell'inchiostro; lo spazio finale dice che la riga nuova inizia una parola.
                    .setPreContext(if (precedente.isEmpty()) "" else "$precedente ".takeLast(20))
                    .setWritingArea(WritingArea(riga.larghezza.coerceAtLeast(1f), altezza.coerceAtLeast(1f)))
                    .build()
                val risultato = client.recognize(inchiostro(riga, (altezza - riga.altezza) / 2f), contesto).attendi()
                val testo = risultato.candidates.firstOrNull()?.text.orEmpty()
                testi += testo
                if (testo.isNotBlank()) precedente = testo
            }
            testi.joinToString("\n")
        }
    }

    /** Libera il riconoscitore (memoria nativa del modello); si ricrea da solo al prossimo uso. */
    suspend fun chiudi() = mutex.withLock {
        riconoscitore?.close()
        riconoscitore = null
    }

    /**
     * Una riga come Ink di ML Kit, spostata nell'origine dell'area di scrittura. I tempi dei nostri
     * tratti ripartono da 0 per ogni tratto: li si mette in fila con una pausa di penna alzata.
     */
    private fun inchiostro(riga: Righe.Riga, margineAlto: Float): Ink {
        val ink = Ink.builder()
        var inizio = 0L
        for (t in riga.tratti) {
            val tratto = Ink.Stroke.builder()
            val p = t.punti
            var ultimo = inizio
            for (i in 0 until t.quanti) {
                val o = i * Tratto.CAMPI
                ultimo = max(ultimo, inizio + p[o + 5].toLong())
                tratto.addPoint(Ink.Point.create(p[o] - riga.sinistra, p[o + 1] - riga.alto + margineAlto, ultimo))
            }
            ink.addStroke(tratto.build())
            inizio = ultimo + PAUSA_MS
        }
        return ink.build()
    }

    private const val PAUSA_MS = 120L

    /** Attende un Task di Play Services senza passare dal main thread. */
    private suspend fun <T> Task<T>.attendi(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener({ it.run() }) { t ->
            when {
                t.isSuccessful -> cont.resume(t.result)
                t.isCanceled -> cont.cancel()
                else -> cont.resumeWithException(t.exception ?: IllegalStateException("Operazione di ML Kit non riuscita"))
            }
        }
    }
}
