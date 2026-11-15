package it.frumorn.tratto.editor

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRotateTest {
    @Test
    fun quartDeTourHoraireEchangeLesAxes() {
        // (x,y) → (H−y, x) : le haut du portrait devient le côté droit du paysage.
        assertEquals(1414f to 500f, ruotaPunto(500f, 0f, 1000f, 1414f, 1))
        assertEquals(0f to 500f, ruotaPunto(500f, 1414f, 1000f, 1414f, 1))
        assertEquals(1414f to 1000f, dimensioniDopoRotazione(1000f, 1414f, 1))
        assertEquals(1000f to 1414f, dimensioniDopoRotazione(1000f, 1414f, 2))
    }

    @Test
    fun unTraitSuitLaPage() {
        val t = Tratto(1, Penna.PENNA, 0, 2f, floatArrayOf(100f, 200f, 1f, -1f, -1f, 0f))
        val r = ruotaTratto(t, 1000f, 1414f, 1)
        assertEquals(1414f - 200f, r.punti[0], 0.01f)
        assertEquals(100f, r.punti[1], 0.01f)
        val retour = ruotaTratto(r, 1414f, 1000f, 3)
        assertEquals(100f, retour.punti[0], 0.01f)
        assertEquals(200f, retour.punti[1], 0.01f)
    }
}
