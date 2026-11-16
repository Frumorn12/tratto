package it.frumorn.tratto.editor

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il tasto della S Pen del Tab S10 FE arriva dal digitalizzatore, non dal Bluetooth.
 * Android lo segnala come bit del pulsante, oppure come punta già gomma.
 */
class StylusButtonTest {
    @Test
    fun ilTastoLateraleConta() {
        assertTrue(StylusButton.tasto(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertTrue(StylusButton.tasto(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_SECONDARY))
        assertTrue(StylusButton.tasto(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_SECONDARY))
        assertTrue(StylusButton.tasto(MotionEvent.TOOL_TYPE_ERASER, MotionEvent.BUTTON_STYLUS_PRIMARY))
    }

    @Test
    fun unDitoNonHaIlTasto() {
        assertFalse(StylusButton.tasto(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertFalse(StylusButton.stilo(MotionEvent.TOOL_TYPE_FINGER))
        assertFalse(StylusButton.gommaTemporanea(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.BUTTON_STYLUS_PRIMARY))
    }

    @Test
    fun laPuntaGommaCancellaAncheSenzaBit() {
        assertFalse(StylusButton.tasto(MotionEvent.TOOL_TYPE_ERASER, 0))
        assertTrue(StylusButton.gommaTemporanea(MotionEvent.TOOL_TYPE_ERASER, 0))
        assertTrue(StylusButton.gommaTemporanea(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY))
        assertFalse(StylusButton.gommaTemporanea(MotionEvent.TOOL_TYPE_STYLUS, 0))
    }
}
