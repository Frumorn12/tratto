package it.frumorn.tratto.editor

import android.view.MotionEvent

/**
 * Riconosce il doppio clic del tasto laterale della S Pen.
 *
 * La S Pen del Tab S6 Lite e' passiva: il tasto si legge solo quando la penna e' vicina allo
 * schermo (hover, circa 1 cm) o lo tocca. Qui si contano solo le pressioni in hover, cosi' tenere
 * premuto il tasto mentre si scrive (gomma temporanea) non apre note per sbaglio.
 */
class TastoPenna(private val alDoppioClic: () -> Unit) {
    private var premuto = false
    private var ultimaPressione = 0L

    fun evento(e: MotionEvent) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return
        val ora = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (ora && !premuto) {
            val inHover = e.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                e.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
            if (inHover) {
                val t = e.eventTime
                if (t - ultimaPressione in 1..INTERVALLO_MS) {
                    ultimaPressione = 0L
                    alDoppioClic()
                } else {
                    ultimaPressione = t
                }
            }
        }
        premuto = ora
    }

    companion object {
        const val INTERVALLO_MS = 450L
    }
}
