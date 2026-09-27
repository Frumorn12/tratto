package it.frumorn.tratto.ui.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.editor.Anteprima
import it.frumorn.tratto.editor.Documento
import it.frumorn.tratto.editor.EditorView
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.FoglioView
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.scrittura.BellaScrittura
import it.frumorn.tratto.scrittura.Stile
import it.frumorn.tratto.scrittura.Trascrittore
import it.frumorn.tratto.pdf.PdfSfondo
import it.frumorn.tratto.ui.StatoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Una nota aperta: tiene la vista di scrittura, gli strumenti e i salvataggi.
 * I tratti si salvano un secondo dopo l'ultima modifica e comunque alla chiusura.
 */
class SessioneEditor(private val stato: StatoApp, val id: String, context: Context) {
    val vista = EditorView(context)
    var documento by mutableStateOf<Documento?>(null)
        private set
    var strumenti by mutableStateOf(stato.preferenze.strumenti())
        private set
    var puoAnnullare by mutableStateOf(false)
        private set
    var puoRipetere by mutableStateOf(false)
        private set
    var pagina by mutableIntStateOf(0)
        private set
    var pagine by mutableIntStateOf(1)
        private set
    var selezione by mutableStateOf(false)
        private set
    var ultimoScorrimento by mutableStateOf(0L)
        private set
    var titolo by mutableStateOf("")
        private set

    private var pdf: PdfSfondo? = null
    private var salvataggio: Job? = null
    private var chiusa = false

    init {
        vista.strumenti = strumenti
        vista.disegnaConDita = stato.preferenze.disegnaConDita
        vista.alTratto = { aggiornaStato(); programmaSalvataggio() }
        vista.alCambioSelezione = {
            selezione = it
            // Senza selezione il pannello della trascrizione non ha piu' senso.
            if (!it && trascrizione !is Trascrizione.InCorso) trascrizione = null
        }
        vista.alCambioPagina = { pagina = it; ultimoScorrimento = System.currentTimeMillis() }
        vista.alTrattiFiniti = { p, nuovi -> calcola(p, nuovi) }
        applicaColori()
    }

    fun applicaColori(scuro: Boolean = false, carta: Int = 0xFFFFFFFF.toInt(), scrivania: Int = 0xFFF2F2F4.toInt(), righe: Int = 0xFFD9D6EC.toInt()) {
        vista.foglio.colorePagina = carta
        vista.foglio.coloreScrivania = scrivania
        vista.foglio.coloreRighe = righe
        vista.foglio.invalidate()
    }

    fun apri() {
        stato.scope.launch {
            val doc = withContext(Dispatchers.IO) {
                stato.archivio.caricaIndice()
                val info: NotaInfo = stato.archivio.note.value.find { it.id == id } ?: return@withContext null
                Documento(stato.archivio, info).also { d ->
                    d.apri()
                    val f = stato.archivio.filePdf(id)
                    if (f.exists()) pdf = runCatching { PdfSfondo(f) { vista.foglio.postInvalidate() } }.getOrNull()
                    // La prima pagina subito, cosi' il primo fotogramma e' gia' completo.
                    d.pagine.firstOrNull()?.let { d.caricaTratti(it) }
                }
            } ?: return@launch
            if (chiusa) return@launch
            titolo = doc.info.titolo
            vista.foglio.pdf = pdf
            vista.documento = doc
            doc.alCambio = { aggiornaStato() }
            documento = doc
            aggiornaStato()
            vista.post { vista.preriscalda() }
            // Le altre pagine in sottofondo, a partire da quelle vicine.
            withContext(Dispatchers.IO) {
                for (p in doc.pagine) {
                    if (chiusa) break
                    if (!p.caricata) { doc.caricaTratti(p); vista.post { vista.ridisegna() } }
                }
            }
        }
    }

    private fun aggiornaStato() {
        val d = documento ?: return
        puoAnnullare = d.puoAnnullare
        puoRipetere = d.puoRipetere
        pagine = d.pagine.size
        pagina = vista.foglio.paginaCorrente()
    }

    fun cambiaStrumenti(nuovo: StatoStrumenti) {
        strumenti = nuovo
        vista.strumenti = nuovo
        stato.preferenze.salvaStrumenti(nuovo)
    }

    /** Passa dalla gomma all'ultima penna e viceversa (azione del doppio clic, se scelta). */
    fun alternaGomma() {
        val st = strumenti
        cambiaStrumenti(st.copy(strumento = if (st.strumento == it.frumorn.tratto.editor.Strumento.GOMMA) it.frumorn.tratto.editor.Strumento.PENNA else it.frumorn.tratto.editor.Strumento.GOMMA))
    }

    fun annulla() {
        documento?.annulla()
        vista.chiudiSelezione()
        vista.ridisegna()
        aggiornaStato()
        programmaSalvataggio()
    }

    fun ripeti() {
        documento?.ripeti()
        vista.chiudiSelezione()
        vista.ridisegna()
        aggiornaStato()
        programmaSalvataggio()
    }

    fun aggiungiPagina() {
        val d = documento ?: return
        d.aggiungiPagina(d.pagine.lastIndex)
        vista.ridisegna()
        aggiornaStato()
        vista.foglio.limita()
        scorriA(d.pagine.lastIndex)
        programmaSalvataggio()
    }

    fun eliminaPagina() {
        val d = documento ?: return
        d.eliminaPagina(vista.foglio.paginaCorrente())
        vista.foglio.dimenticaCache()
        vista.ridisegna()
        aggiornaStato()
        programmaSalvataggio()
    }

    fun cambiaSfondo(s: Sfondo) {
        val d = documento ?: return
        d.cambiaSfondo(vista.foglio.paginaCorrente(), s)
        vista.ridisegna()
        programmaSalvataggio()
    }

    /** Scorre con un'animazione fino all'inizio della pagina [i]. */
    fun scorriA(i: Int) {
        val f = vista.foglio
        val da = f.ty
        val a = (-f.cimaPagina(i) * f.scala + f.spazioSopra).coerceIn(f.limiteTy)
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { f.impostaVista(f.scala, f.tx, da + (a - da) * (it.animatedValue as Float)) }
            start()
        }
    }

    fun rinomina(nuovo: String) {
        titolo = nuovo
        stato.rinomina(id, nuovo.trim())
    }

    /** Pannello della trascrizione: null se chiuso. */
    var trascrizione by mutableStateOf<Trascrizione?>(null)
        private set

    sealed interface Trascrizione {
        /** Il modello italiano non c'e' ancora: si chiede di scaricarlo. */
        data object ServeModello : Trascrizione
        data object InCorso : Trascrizione
        data class Pronta(val testo: String) : Trascrizione
        data class Errore(val messaggio: String) : Trascrizione
    }

    private var contesto: android.content.Context = context.applicationContext

    fun trascrivi() {
        val tratti = vista.copiaSelezione()
        if (tratti.isEmpty()) return
        trascrizione = Trascrizione.InCorso
        stato.scope.launch {
            trascrizione = try {
                if (!Trascrittore.modelloPronto(contesto)) Trascrizione.ServeModello
                else Trascrizione.Pronta(Trascrittore.trascrivi(contesto, tratti).trim())
            } catch (e: Exception) {
                Trascrizione.Errore(e.message ?: "Non sono riuscito a leggere la scrittura")
            }
        }
    }

    fun scaricaModello() {
        trascrizione = Trascrizione.InCorso
        stato.scope.launch {
            if (Trascrittore.scaricaModello(contesto)) trascrivi()
            else trascrizione = Trascrizione.Errore((Trascrittore.stato.value as? Trascrittore.StatoModello.Errore)?.messaggio ?: "Download non riuscito: controlla la connessione")
        }
    }

    fun chiudiTrascrizione() { trascrizione = null }

    /** Riscrive il testo con la "bella scrittura" al posto dei tratti selezionati, nello stesso punto. */
    fun riscrivi(testo: String, stile: Stile) {
        val originali = vista.copiaSelezione()
        val sel = vista.selezione ?: return
        if (originali.isEmpty() || testo.isBlank()) return
        stato.scope.launch {
            val nuovi = withContext(Dispatchers.Default) {
                BellaScrittura.prepara(contesto)
                val prevalente = originali.groupingBy { it.colore }.eachCount().maxByOrNull { it.value }!!.key
                val penna = originali.groupingBy { it.penna }.eachCount().maxByOrNull { it.value }!!.key
                    .let { if (it == Penna.EVIDENZIATORE) Penna.PENNA else it }
                val spessore = originali.filter { it.penna == penna }.map { it.spessore }.sorted().let { it.getOrElse(it.size / 2) { 3.2f } }
                val box = sel.scatola
                val larghezza = (FoglioView.LARGHEZZA - 40f - box.left).coerceAtLeast(box.width())
                BellaScrittura.componi(
                    testo, stile, box.left, box.top, BellaScrittura.altezzaXStimata(originali), larghezza,
                    prevalente, penna, spessore, idNuovo = { vista.nuovoId() },
                )
            }
            vista.sostituisciSelezione(nuovi)
            trascrizione = null
        }
    }

    /**
     * Note matematiche: se i tratti appena scritti completano un "=" dopo un'espressione, il
     * risultato viene scritto a mano accanto. Le chiamate partono subito e in ordine, come chiede
     * il Calcolatore (la finestra per le due barre dell'uguale si misura al momento della chiamata).
     */
    private var calcoli: kotlinx.coroutines.Job? = null

    private fun calcola(p: it.frumorn.tratto.editor.PaginaViva, nuovi: List<it.frumorn.tratto.data.Tratto>) {
        if (!stato.preferenze.calcoliAutomatici) return
        val d = documento ?: return
        val tutti = synchronized(p) { ArrayList(p.tratti) }
        val stile = stato.preferenze.stileBellaScrittura
        val precedente = calcoli
        calcoli = stato.scope.launch {
            precedente?.join()
            val r = runCatching {
                withContext(Dispatchers.Default) { it.frumorn.tratto.calcolo.Calcolatore.dopoTratto(contesto, tutti, nuovi, stile) { d.nuovoIdTratto() } }
            }.getOrNull() ?: return@launch
            android.util.Log.i("Tratto", "calcolo: ${r.espressione} = ${r.valore}")
            if (chiusa || d.pagine.none { it === p }) return@launch
            r.tratti.forEach { t -> p.stroke(t) }
            d.esegui(it.frumorn.tratto.editor.Modifica(p, emptyList(), r.tratti))
            vista.ridisegna()
            aggiornaStato()
            programmaSalvataggio()
        }
    }

    fun copia() { stato.appunti = vista.copiaSelezione() }
    fun taglia() { copia(); vista.eliminaSelezione() }
    fun incolla() { vista.incolla(stato.appunti) }
    val haAppunti get() = stato.appunti.isNotEmpty()

    fun programmaSalvataggio() {
        salvataggio?.cancel()
        salvataggio = stato.scope.launch {
            delay(1000)
            withContext(Dispatchers.IO) { documento?.salva() }
        }
    }

    /** Salva, aggiorna la copertina e libera le risorse. */
    fun chiudi() {
        if (chiusa) return
        chiusa = true
        salvataggio?.cancel()
        val d = documento
        val p = pdf
        stato.scope.launch {
            withContext(Dispatchers.IO) {
                if (d != null) {
                    val cambiata = d.modificato
                    d.salva()
                    val copertinaMancante = !stato.archivio.fileAnteprima(id).exists()
                    if (cambiata || copertinaMancante) runCatching { Anteprima.crea(stato.archivio, d, p) }
                }
                p?.close()
            }
            stato.versioneAnteprime++
        }
    }

    /** Salva tutto e attende la fine: da chiamare prima di esportare. */
    suspend fun salvaEAttendi(): NotaInfo? {
        salvataggio?.cancel()
        val d = documento ?: return null
        withContext(Dispatchers.IO) { d.salva() }
        return stato.archivio.note.value.find { it.id == id }
    }

    fun paginaCorrente() = documento?.pagine?.getOrNull(vista.foglio.paginaCorrente())?.pagina

    /** Salvataggio immediato quando l'app va in secondo piano. */
    fun salvaOra() {
        salvataggio?.cancel()
        val d = documento ?: return
        stato.scope.launch(Dispatchers.IO) { d.salva() }
    }
}
