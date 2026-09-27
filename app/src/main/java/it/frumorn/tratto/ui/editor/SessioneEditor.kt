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
        vista.alCambioSelezione = { selezione = it }
        vista.alCambioPagina = { pagina = it; ultimoScorrimento = System.currentTimeMillis() }
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

    /** Salvataggio immediato quando l'app va in secondo piano. */
    fun salvaOra() {
        salvataggio?.cancel()
        val d = documento ?: return
        stato.scope.launch(Dispatchers.IO) { d.salva() }
    }
}
