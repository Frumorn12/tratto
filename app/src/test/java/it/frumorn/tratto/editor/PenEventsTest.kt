package it.frumorn.tratto.editor

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Se piego un po la penna non riesce bene a prendere ancora il tratto" (Andrea, 04/11).
 *
 * Con la penna inclinata la mano si appoggia prima e di piu': il palmo puo' toccare lo schermo prima
 * della punta, o restarci quando la punta si alza. Prima l'editor guardava solo il primo puntatore:
 * se il primo era il palmo, la penna arrivava come POINTER_DOWN e il tratto non partiva; se la punta
 * si alzava con il palmo giu' (POINTER_UP), il tratto non si chiudeva. Ora la penna si segue per id.
 */
class PenEventsTest {
    private val penna = MotionEvent.TOOL_TYPE_STYLUS
    private val dito = MotionEvent.TOOL_TYPE_FINGER

    private fun smista(azione: Int, indice: Int, strumenti: IntArray, ids: IntArray, attiva: Int = -1) =
        PenEvents.smista(azione, indice, strumenti, ids, attiva)

    @Test
    fun pennaDaSola() {
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_DOWN, 0), smista(MotionEvent.ACTION_DOWN, 0, intArrayOf(penna), intArrayOf(0)))
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_MOVE, 0), smista(MotionEvent.ACTION_MOVE, 0, intArrayOf(penna), intArrayOf(0), attiva = 0))
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_UP, 0), smista(MotionEvent.ACTION_UP, 0, intArrayOf(penna), intArrayOf(0), attiva = 0))
    }

    @Test
    fun palmoGiuPrimaDellaPenna() {
        // Il palmo (id 0) tocca per primo: la penna arriva come POINTER_DOWN e il tratto parte lo stesso.
        assertEquals(
            PenEvents.Smistamento(MotionEvent.ACTION_DOWN, 1),
            smista(MotionEvent.ACTION_POINTER_DOWN, 1, intArrayOf(dito, penna), intArrayOf(0, 1)),
        )
        // I movimenti seguono la penna anche se il primo puntatore e' il palmo.
        assertEquals(
            PenEvents.Smistamento(MotionEvent.ACTION_MOVE, 1),
            smista(MotionEvent.ACTION_MOVE, 0, intArrayOf(dito, penna), intArrayOf(0, 1), attiva = 1),
        )
        // La punta si alza con il palmo ancora giu': il tratto si chiude.
        assertEquals(
            PenEvents.Smistamento(MotionEvent.ACTION_UP, 1),
            smista(MotionEvent.ACTION_POINTER_UP, 1, intArrayOf(dito, penna), intArrayOf(0, 1), attiva = 1),
        )
    }

    @Test
    fun ilPalmoDuranteIlTrattoNonConta() {
        // Il palmo si appoggia e si alza mentre la penna scrive: niente da fare.
        assertEquals(PenEvents.IGNORA, smista(MotionEvent.ACTION_POINTER_DOWN, 1, intArrayOf(penna, dito), intArrayOf(0, 1), attiva = 0))
        assertEquals(PenEvents.IGNORA, smista(MotionEvent.ACTION_POINTER_UP, 1, intArrayOf(penna, dito), intArrayOf(0, 1), attiva = 0))
        // Il palmo resta, la penna si alza per ultima: l'evento e' un UP della penna.
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_UP, 0), smista(MotionEvent.ACTION_UP, 0, intArrayOf(penna), intArrayOf(0), attiva = 0))
    }

    @Test
    fun laPennaTornaGiuColPalmoAppoggiato() {
        // Tra una parola e l'altra il palmo resta giu' (ora e' l'unico, id 1) e la penna (id 2) riscende.
        assertEquals(
            PenEvents.Smistamento(MotionEvent.ACTION_DOWN, 1),
            smista(MotionEvent.ACTION_POINTER_DOWN, 1, intArrayOf(dito, penna), intArrayOf(1, 2)),
        )
    }

    @Test
    fun annullato() {
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_CANCEL, 1), smista(MotionEvent.ACTION_CANCEL, 0, intArrayOf(dito, penna), intArrayOf(0, 1), attiva = 1))
    }

    @Test
    fun soloDitaNonSonoDellaPenna() {
        assertNull(smista(MotionEvent.ACTION_DOWN, 0, intArrayOf(dito), intArrayOf(0)))
        assertNull(smista(MotionEvent.ACTION_POINTER_DOWN, 1, intArrayOf(dito, dito), intArrayOf(0, 1)))
        assertNull(smista(MotionEvent.ACTION_MOVE, 0, intArrayOf(dito, dito), intArrayOf(0, 1)))
    }

    @Test
    fun laGommaDellaPennaContaComePenna() {
        assertEquals(
            PenEvents.Smistamento(MotionEvent.ACTION_DOWN, 0),
            smista(MotionEvent.ACTION_DOWN, 0, intArrayOf(MotionEvent.TOOL_TYPE_ERASER), intArrayOf(0)),
        )
    }

    @Test
    fun pennaAttivaSparita() {
        // Non dovrebbe succedere: se la penna seguita non c'e' piu' nell'evento, il tratto si annulla.
        assertEquals(PenEvents.Smistamento(MotionEvent.ACTION_CANCEL, -1), smista(MotionEvent.ACTION_MOVE, 0, intArrayOf(dito), intArrayOf(0), attiva = 3))
    }

    // ------------------------------------------------------------ pressione

    @Test
    fun pressioneZeroAMetaTrattoSiTiene() {
        // Il tablet con la penna inclinata a volte da' pressione zero per qualche campione: il tratto
        // non si assottiglia di colpo, resta com'era.
        val t = PenEvents.PressioneTenuta()
        assertEquals(0.4f, t.correggi(0.4f), 0f)
        assertEquals(0.5f, t.correggi(0.5f), 0f)
        assertEquals(0.5f, t.correggi(0f), 0f)
        assertEquals(0.5f, t.correggi(0f), 0f)
        assertEquals(0.3f, t.correggi(0.3f), 0f)
    }

    @Test
    fun trattoTuttoAPressioneZero() {
        // Penna molto inclinata: pressione zero dall'inizio alla fine. Il tratto si vede lo stesso.
        val t = PenEvents.PressioneTenuta()
        assertEquals(PenEvents.PressioneTenuta.PREDEFINITA, t.correggi(0f), 0f)
        assertEquals(PenEvents.PressioneTenuta.PREDEFINITA, t.correggi(0f), 0f)
        t.inizia()
        assertEquals(0.2f, t.correggi(0.2f), 0f)
    }

    @Test
    fun ogniTrattoRicominciaDaCapo() {
        val t = PenEvents.PressioneTenuta()
        t.correggi(0.9f)
        t.inizia()
        assertEquals(PenEvents.PressioneTenuta.PREDEFINITA, t.correggi(0f), 0f)
    }
}
