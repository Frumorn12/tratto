package it.frumorn.tratto.notarapida

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Preferenze
import it.frumorn.tratto.calcolo.Calcolatore
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.Anteprima
import it.frumorn.tratto.editor.Documento
import it.frumorn.tratto.editor.EditorView
import it.frumorn.tratto.editor.FoglioView
import it.frumorn.tratto.editor.Modifica
import it.frumorn.tratto.editor.PaginaViva
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.Strumento
import it.frumorn.tratto.scrittura.Trascrittore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La nota del foglietto: la stessa superficie di scrittura dell'editor ([EditorView], inchiostro a
 * bassa latenza, pressione, appoggio del palmo) su un [Documento] che esiste solo in memoria finche'
 * non si scrive il primo tratto. Solo allora la nota entra nell'archivio: un foglietto aperto e
 * chiuso senza scrivere non lascia note vuote, e se alla chiusura e' stato cancellato tutto la nota
 * sparisce.
 */
class SessioneNotaRapida(context: Context, private val archivio: Archivio, private val preferenze: Preferenze) {
    val vista = EditorView(context)
    var strumenti by mutableStateOf(preferenze.strumenti())
        private set
    var puoAnnullare by mutableStateOf(false)
        private set
    var puoRipetere by mutableStateOf(false)
        private set

    private val info = NotaInfo(
        id = Archivio.nuovoId(), titolo = "", cartellaId = null, creata = 0L, modificata = 0L,
        preferita = false, pagine = 1, sfondo = preferenze.sfondoPredefinito, conPdf = false,
    )
    val id: String get() = info.id

    // La nota non esiste ancora su disco: apri() trova solo file mancanti e crea la pagina vuota.
    // Costa pochissimo, quindi si fa subito: anche il primo tratto trova il documento pronto.
    private val documento = Documento(archivio, info).also { d -> d.apri(); d.pagine.forEach { d.caricaTratti(it) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var salvataggio: Job? = null
    /** La nota e' nell'archivio (dal primo tratto in poi). */
    private var registrata = false
    private var chiusa = false

    init {
        vista.strumenti = strumenti
        vista.disegnaConDita = preferenze.disegnaConDita
        vista.documento = documento
        documento.alCambio = { aggiornaStato() }
        vista.alTratto = { dopoModifica() }
        vista.alTrattiFiniti = { p, nuovi -> calcola(p, nuovi) }
        vista.post { vista.preriscalda() }
        // Il foglietto e' piccolo: la pagina lo riempie in larghezza anche in orizzontale, dove
        // l'editor lascia ampi margini ai lati. Rimane la stessa riga in alto quando si ruota.
        vista.foglio.addOnLayoutChangeListener { _, l, _, r, _, vl, _, vr, _ ->
            val w = r - l
            if (w == 0 || w == vr - vl) return@addOnLayoutChangeListener
            val f = vista.foglio
            val s = w * 0.94f / FoglioView.LARGHEZZA
            val cima = -f.ty / f.scala
            f.impostaVista(s, (w - FoglioView.LARGHEZZA * s) / 2f, -cima * s)
        }
    }

    fun applicaColori(carta: Int, scrivania: Int, righe: Int) {
        vista.foglio.colorePagina = carta
        vista.foglio.coloreScrivania = scrivania
        vista.foglio.coloreRighe = righe
        vista.foglio.invalidate()
    }

    private fun aggiornaStato() {
        puoAnnullare = documento.puoAnnullare
        puoRipetere = documento.puoRipetere
    }

    private fun vuota(): Boolean = documento.pagine.all { p -> synchronized(p) { p.tratti.isEmpty() } }

    /** Dopo ogni tratto, cancellatura o annulla: al primo tratto nasce la nota, poi si salva con calma. */
    private fun dopoModifica() {
        aggiornaStato()
        if (chiusa) return
        if (!registrata) {
            if (vuota()) return
            registrata = true
            val ora = System.currentTimeMillis()
            val nuova = info.copy(titolo = NotaRapida.titolo(ora), creata = ora, modificata = ora)
            // Prima l'indice va letto da disco: scriverlo senza averlo caricato cancellerebbe le altre note.
            LavoriNotaRapida.esegui { archivio.caricaIndice(); archivio.aggiungiNota(nuova); documento.salva() }
            return
        }
        programmaSalvataggio()
    }

    private var calcoli: Job? = null

    /** Le note matematiche anche nel foglietto: dopo "=" il risultato si scrive a mano, come nell'editor. */
    private fun calcola(p: PaginaViva, nuovi: List<Tratto>) {
        if (!Trascrittore.DISPONIBILE || !preferenze.calcoliAutomatici) return
        val tutti = synchronized(p) { ArrayList(p.tratti) }
        val stile = preferenze.stileBellaScrittura
        val precedente = calcoli
        calcoli = scope.launch {
            precedente?.join()
            val r = runCatching {
                withContext(Dispatchers.Default) { Calcolatore.dopoTratto(vista.context, tutti, nuovi, stile) { documento.nuovoIdTratto() } }
            }.getOrNull() ?: return@launch
            if (chiusa) return@launch
            r.tratti.forEach { t -> p.stroke(t) }
            documento.esegui(Modifica(p, emptyList(), r.tratti))
            vista.ridisegna()
            dopoModifica()
        }
    }

    private fun programmaSalvataggio() {
        salvataggio?.cancel()
        salvataggio = scope.launch {
            delay(1000)
            LavoriNotaRapida.esegui { documento.salva() }
        }
    }

    fun cambiaStrumenti(nuovo: StatoStrumenti) {
        strumenti = nuovo
        vista.strumenti = nuovo
        preferenze.salvaStrumenti(nuovo)
    }

    /** Colore della penna corrente, come nel pannello dell'editor: finisce anche tra i recenti. */
    fun scegliColore(colore: Int) {
        val st = strumenti
        val imp = st.corrente
        val recenti = if (colore != imp.colore) (listOf(colore) + st.coloriRecenti.filter { it != colore }).take(6) else st.coloriRecenti
        cambiaStrumenti(st.copy(strumento = Strumento.PENNA, penne = st.penne + (st.penna to imp.copy(colore = colore)), coloriRecenti = recenti))
    }

    /** Passa dalla gomma all'ultima penna e viceversa (doppio clic, se scelto nelle impostazioni). */
    fun alternaGomma() {
        val st = strumenti
        cambiaStrumenti(st.copy(strumento = if (st.strumento == Strumento.GOMMA) Strumento.PENNA else Strumento.GOMMA))
    }

    fun annulla() {
        documento.annulla()
        vista.ridisegna()
        dopoModifica()
    }

    fun ripeti() {
        documento.ripeti()
        vista.ridisegna()
        dopoModifica()
    }

    /**
     * Per "Apri in Tratto": salva tutto e attende la fine, cosi' l'editor legge da disco la nota
     * completa. Restituisce false se non c'e' ancora nessuna nota (niente di scritto).
     */
    suspend fun consegna(): Boolean {
        if (chiusa) return registrata
        chiusa = true
        salvataggio?.cancel()
        scope.cancel()
        if (!registrata) return false
        // L'editor fara' da se' la copertina quando la nota viene chiusa.
        LavoriNotaRapida.attendi { documento.salva() }
        return true
    }

    /** Chiusura del foglietto: salva, fa la copertina per la libreria o toglie la nota se e' vuota. */
    fun chiudi() {
        if (chiusa) return
        chiusa = true
        salvataggio?.cancel()
        scope.cancel()
        if (!registrata) return
        val d = documento
        val vuota = vuota()
        LavoriNotaRapida.esegui {
            if (vuota) {
                archivio.elimina(setOf(info.id))
            } else {
                d.salva()
                runCatching { Anteprima.crea(archivio, d, null) }
                // La libreria riconosce la copertina da id e data di modifica: cosi' la rilegge.
                archivio.tocca(info.id)
            }
        }
    }
}

/**
 * I salvataggi della nota rapida, uno alla volta e nell'ordine in cui sono chiesti (la nota nasce
 * prima di essere salvata, e si elimina solo dopo l'ultimo salvataggio). Vivono quanto il processo:
 * il foglietto si chiude subito e il salvataggio finisce dopo.
 */
object LavoriNotaRapida {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val coda = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + coda)

    fun esegui(lavoro: () -> Unit) {
        scope.launch {
            runCatching(lavoro).onFailure { android.util.Log.w("Tratto", "nota rapida: salvataggio non riuscito", it) }
        }
    }

    suspend fun attendi(lavoro: () -> Unit) {
        withContext(coda) { runCatching(lavoro) }
    }
}
