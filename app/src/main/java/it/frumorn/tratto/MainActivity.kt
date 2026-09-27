package it.frumorn.tratto

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import it.frumorn.tratto.data.DoppioClic
import it.frumorn.tratto.data.Preferenze
import it.frumorn.tratto.editor.TastoPenna
import it.frumorn.tratto.ui.App
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.editor.SessioneEditor
import it.frumorn.tratto.ui.theme.TrattoTheme

class MainActivity : ComponentActivity() {
    private lateinit var stato: StatoApp
    private var sessione: SessioneEditor? = null
    private val tasto = TastoPenna { doppioClic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(0, 0), navigationBarStyle = SystemBarStyle.auto(0, 0))
        super.onCreate(savedInstanceState)
        val preferenze = Preferenze(this)
        stato = StatoApp(applicationContext, TrattoApp.archivio(this), preferenze)
        stato.caricaIndice()
        if (savedInstanceState == null) gestisci(intent)
        setContent {
            TrattoTheme(stato.preferenze.tema, stato.preferenze.spigoloVivo) {
                App(stato) { sessione = it }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        gestisci(intent)
    }

    private fun gestisci(i: Intent?) {
        if (i?.component?.className?.endsWith(".NuovaNota") == true) { stato.nuovaNota(origine = "penna"); return }
        when (i?.action) {
            AZIONE_NUOVA_NOTA, Intent.ACTION_ASSIST, Intent.ACTION_CREATE_NOTE -> stato.nuovaNota(origine = "penna")
            Intent.ACTION_VIEW -> i.data?.let { importaSePdf(it, i.type) }
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { importaSePdf(it, i.type) }
        }
    }

    private fun importaSePdf(uri: Uri, tipo: String?) {
        val t = tipo ?: contentResolver.getType(uri)
        if (t == "application/pdf" || uri.path?.endsWith(".pdf", ignoreCase = true) == true) stato.importaPdf(uri)
    }

    // Il tasto della penna si ascolta a livello di finestra, cosi' funziona su ogni schermata.
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        tasto.evento(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tasto.evento(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun doppioClic() {
        when (stato.preferenze.doppioClic) {
            DoppioClic.NUOVA_NOTA -> stato.nuovaNota(origine = "penna")
            DoppioClic.GOMMA -> sessione?.alternaGomma()
            DoppioClic.NIENTE -> Unit
        }
    }

    override fun onPause() {
        super.onPause()
        sessione?.salvaOra()
    }

    companion object {
        const val AZIONE_NUOVA_NOTA = "it.frumorn.tratto.NUOVA_NOTA"
    }
}
