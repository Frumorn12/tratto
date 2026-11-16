package it.frumorn.tratto.editor

import android.view.MotionEvent

/**
 * Tasto laterale dello stilo, come lo descrive Android
 * ([MotionEvent.BUTTON_STYLUS_PRIMARY], [MotionEvent.TOOL_TYPE_ERASER]).
 *
 * La S Pen del Galaxy Tab S10 FE non ha Bluetooth: il tasto arriva dal digitalizzatore.
 * Punta sullo schermo e tasto premuto (o punta rovesciata) → gomma.
 * Tasto premuto a lungo con la punta a mezz'aria → menu. Il doppio clic resta un clic breve.
 */
object StylusButton {
    private const val TASTI = MotionEvent.BUTTON_STYLUS_PRIMARY or
        MotionEvent.BUTTON_STYLUS_SECONDARY or
        MotionEvent.BUTTON_SECONDARY

    fun stilo(tipo: Int) = tipo == MotionEvent.TOOL_TYPE_STYLUS || tipo == MotionEvent.TOOL_TYPE_ERASER

    /** Il tasto laterale è giù. Non conta la punta-gomma: quella è [gommaTemporanea]. */
    fun tasto(tipo: Int, tasti: Int) = stilo(tipo) && (tasti and TASTI) != 0

    /**
     * Si cancella: tasto laterale premuto, oppure Android segnala già la punta come gomma
     * (il Tab tiene il tasto e manda [MotionEvent.TOOL_TYPE_ERASER]).
     */
    fun gommaTemporanea(tipo: Int, tasti: Int) = tipo == MotionEvent.TOOL_TYPE_ERASER || tasto(tipo, tasti)
}
