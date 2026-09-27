package it.frumorn.tratto.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager

/**
 * Luminosita' ferma mentre si scrive. Su questo tablet (Android 16 senza file di configurazione
 * del display) la luminosita' automatica non aspetta niente prima di cambiare, e in penombra il
 * sensore da' solo 0, 1, 2 o 3 lux: la mano che scrive gli fa ombra e lo schermo sale e scende
 * ogni pochi secondi. Con la penna sul foglio la finestra tiene la luminosita' che c'era; qualche
 * secondo dopo l'ultimo tratto torna quella automatica. Con la luminosita' manuale non fa niente.
 */
class LuceStabile(private val activity: Activity, private val attiva: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var ferma = false
    private val sblocca = Runnable {
        ferma = false
        imposta(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    /** Da chiamare con ogni evento di tocco della finestra. */
    fun evento(e: MotionEvent) {
        val t = e.getToolType(0)
        if (t != MotionEvent.TOOL_TYPE_STYLUS && t != MotionEvent.TOOL_TYPE_ERASER) return
        if (e.actionMasked != MotionEvent.ACTION_DOWN && e.actionMasked != MotionEvent.ACTION_MOVE) return
        if (!attiva() || !automatica()) return
        if (!ferma) {
            val v = attuale() ?: return
            ferma = true
            imposta(v)
        }
        handler.removeCallbacks(sblocca)
        handler.postDelayed(sblocca, ATTESA_MS)
    }

    /** Quando la finestra va in secondo piano: la luminosita' torna subito del sistema. */
    fun rilascia() {
        handler.removeCallbacks(sblocca)
        if (ferma) sblocca.run()
    }

    private fun automatica(): Boolean = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** Con la luminosita' automatica il sistema tiene aggiornato questo valore (0-255). */
    private fun attuale(): Float? = runCatching {
        (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.004f, 1f)
    }.getOrNull()

    private fun imposta(valore: Float) {
        val lp = activity.window.attributes
        if (lp.screenBrightness == valore) return
        lp.screenBrightness = valore
        activity.window.attributes = lp
    }

    companion object {
        /** Pausa dopo l'ultimo tratto prima di ridare la luminosita' al sistema. */
        const val ATTESA_MS = 10_000L
    }
}
