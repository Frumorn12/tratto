package it.frumorn.tratto.notarapida

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import it.frumorn.tratto.MainActivity
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.data.DoppioClic
import it.frumorn.tratto.data.Preferenze
import it.frumorn.tratto.editor.TastoPenna
import it.frumorn.tratto.ui.LuceStabile
import it.frumorn.tratto.ink.Pennelli
import it.frumorn.tratto.ui.theme.TrattoTheme
import kotlinx.coroutines.launch

/**
 * Il foglietto della nota rapida: si apre sopra l'app in primo piano (che resta visibile, velata),
 * in un task tutto suo e fuori dai recenti. Si chiude con "Fatto", con Indietro, toccando fuori o
 * andando altrove; la nota si salva da sola.
 */
class NotaRapidaActivity : ComponentActivity() {
    private lateinit var preferenze: Preferenze
    private lateinit var sessione: SessioneNotaRapida
    /** Istante (uptime) dell'ultimo evento della penna, hover compreso: vedi [NotaRapida.toccoFuoriChiude]. */
    private var ultimaPenna = 0L
    /** Vero quando parte l'animazione d'uscita: alla fine l'activity si chiude. */
    private var inUscita by mutableStateOf(false)
    private var chiusa = false

    // Doppio clic dentro il foglietto: la nota e' gia' nuova, quindi al massimo alterna la gomma.
    private val tasto = TastoPenna { if (preferenze.doppioClic == DoppioClic.GOMMA) sessione.alternaGomma() }
    private val luce = LuceStabile(this) { preferenze.luceStabile }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Sopra il velo scuro le icone delle barre di sistema restano chiare.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Niente animazione della finestra: velo e foglietto li anima Compose, con le curve dell'app.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        preferenze = Preferenze(this)
        Pennelli.sensibilita = preferenze.sensibilita
        sessione = SessioneNotaRapida(this, TrattoApp.archivio(this), preferenze)
        setContent {
            TrattoTheme(preferenze.tema, preferenze.spigoloVivo) {
                Foglietto(
                    sessione = sessione,
                    inUscita = inUscita,
                    ultimaPenna = { ultimaPenna },
                    onChiudi = ::chiudi,
                    onApriInTratto = ::apriInTratto,
                    onUscitaFinita = { finish() },
                )
            }
        }
    }

    // Una seconda richiesta mentre il foglietto e' aperto arriva a onNewIntent (singleTask) e non fa niente.

    private fun penna(ev: MotionEvent) {
        val t = ev.getToolType(0)
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) ultimaPenna = ev.eventTime
        tasto.evento(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        penna(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        penna(ev)
        luce.evento(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Chiusura con l'animazione: il salvataggio parte subito e finisce anche dopo la chiusura. */
    private fun chiudi() {
        if (chiusa) return
        chiusa = true
        sessione.chiudi()
        inUscita = true
    }

    private fun apriInTratto() {
        if (chiusa) return
        chiusa = true
        lifecycleScope.launch {
            val esiste = sessione.consegna()
            val i = Intent(this@NotaRapidaActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Senza niente di scritto non c'e' una nota da aprire: Tratto ne crea una nuova.
            if (esiste) i.setAction(MainActivity.AZIONE_APRI_NOTA).putExtra(MainActivity.EXTRA_NOTA, sessione.id)
            else i.setAction(MainActivity.AZIONE_NUOVA_NOTA)
            startActivity(i)
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Andando altrove (Home, schermo spento, un'altra app) il foglietto si chiude e la nota resta salvata.
        if (!isChangingConfigurations && !isFinishing && !chiusa) {
            chiusa = true
            sessione.chiudi()
            finish()
        }
    }

    companion object {
        /** Apre il foglietto sopra quello che c'e' sullo schermo (anche da un servizio). */
        fun apri(context: Context) {
            context.startActivity(Intent(context, NotaRapidaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
