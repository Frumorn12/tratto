package it.frumorn.tratto.ingressi

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import it.frumorn.tratto.notarapida.NotaRapidaActivity

/**
 * Apre la nota rapida da qualsiasi app con il pulsante Accessibilita' (il pulsante mobile sullo
 * schermo) o con la scorciatoia dei tasti del volume. Non chiede eventi ne' il contenuto delle
 * finestre: non legge niente di quello che c'e' sullo schermo.
 *
 * Perche' non il doppio clic del tasto della penna fuori da Tratto (verificato sui sorgenti di
 * Android 16):
 *  - per vedere il tasto servirebbero gli eventi della penna (setMotionEventSources); ma un servizio
 *    che li chiede li consuma, e la penna smetterebbe di scrivere in tutte le altre app;
 *  - la variante che li osserva senza consumarli (setObservedMotionEventSources) e' un'API nascosta
 *    e il sistema la ignora se manca ACCESSIBILITY_MOTION_EVENT_OBSERVING, permesso riservato alle
 *    app firmate con la chiave del sistema;
 *  - il tasto arriva anche come KEYCODE_STYLUS_BUTTON_PRIMARY, ma PhoneWindowManager lo tiene per
 *    se' (niente PASS_TO_USER): non lo vedono ne' le app ne' i servizi di accessibilita'.
 *
 * Un servizio legato dal sistema puo' aprire activity anche da dietro (BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS).
 */
class ServizioNotaRapida : AccessibilityService() {
    private val pulsante = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            NotaRapidaActivity.apri(this@ServizioNotaRapida)
        }
    }

    override fun onServiceConnected() {
        accessibilityButtonController.registerAccessibilityButtonCallback(pulsante)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(pulsante)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        fun componente(context: Context) = ComponentName(context, ServizioNotaRapida::class.java)

        /** Il servizio e' attivo nelle impostazioni di Accessibilita'? */
        fun attivo(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            val nome = componente(context)
            return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val s = it.resolveInfo?.serviceInfo ?: return@any false
                s.packageName == nome.packageName && s.name == nome.className
            }
        }
    }
}
