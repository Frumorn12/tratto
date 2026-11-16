package it.frumorn.tratto.editor

import android.view.MotionEvent

/**
 * Riconosce il doppio clic del tasto laterale della S Pen.
 *
 * La S Pen del Tab S10 FE (e del Tab S6 Lite) è passiva: il tasto si legge solo quando la penna è
 * vicina allo schermo o lo tocca. Si contano solo i clic brevi a mezz'aria. Tenerlo premuto mentre
 * si scrive è la gomma temporanea; tenerlo premuto a lungo a mezz'aria apre il menu. Nessuno dei
 * due è un clic.
 */
class PenButton(private val alDoppioClic: () -> Unit) {
    private var premuto = false
    private var inizio = 0L
    private var ultimaPressione = 0L

    fun evento(e: MotionEvent) {
        if (!StylusButton.stilo(e.getToolType(0))) return
        val ora = StylusButton.tasto(e.getToolType(0), e.buttonState)
        val inAria = inAria(e)
        if (ora && !premuto && inAria) inizio = e.eventTime
        if (!ora && premuto && inizio > 0L) {
            val tenuto = e.eventTime - inizio
            if (inAria && tenuto in 0 until SOGLIA_LUNGA_MS) {
                val t = e.eventTime
                if (t - ultimaPressione in 1..INTERVALLO_MS) {
                    ultimaPressione = 0L
                    alDoppioClic()
                } else {
                    ultimaPressione = t
                }
            }
            inizio = 0L
        }
        premuto = ora
    }

    private fun inAria(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_HOVER_MOVE || a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_EXIT) return true
        // Il tasto premuto con la punta giù arriva come evento generico, ma la pressione non è zero.
        return (a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) && e.pressure == 0f
    }

    companion object {
        const val INTERVALLO_MS = 450L
        /** Sotto la soglia di pressione lunga di Android: un clic, non il menu. */
        const val SOGLIA_LUNGA_MS = 400L
    }
}
