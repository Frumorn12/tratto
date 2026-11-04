package it.frumorn.tratto.ink

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Se piego un po la penna non riesce bene a prendere ancora il tratto" (Andrea, 04/11). Con la
 * penna inclinata la pressione scende; con la sensibilita' Forte la penna a sfera a pressione zero
 * scendeva all'1% dello spessore e il pennello a zero: il tratto c'era ma non si vedeva. Ora Forte
 * allarga l'escursione solo verso l'alto: verso il basso resta quella della sensibilita' Media.
 */
class SensibilitaTest {
    @Test
    fun conSensibilitaMediaNonCambiaNiente() {
        assertEquals(0.34f, Sensibilita.minimo(0.34f, 1f), 0.0001f)
        assertEquals(0.1f, Sensibilita.minimo(0.1f, 1f), 0.0001f)
        assertEquals(1.45f, Sensibilita.massimo(1.45f, 1f), 0.0001f)
    }

    @Test
    fun conSensibilitaForteIlFiloResta() {
        // Prima: 1 - 0,66 x 1,5 = 0,01 (penna a sfera) e 1 - 0,9 x 1,5 < 0 (pennello).
        assertEquals(0.34f, Sensibilita.minimo(0.34f, 1.5f), 0.0001f)
        assertEquals(0.1f, Sensibilita.minimo(0.1f, 1.5f), 0.0001f)
        // Verso l'alto Forte allarga come prima.
        assertEquals(1.675f, Sensibilita.massimo(1.45f, 1.5f), 0.0001f)
    }

    @Test
    fun conSensibilitaLeggeraSiAllargaMeno() {
        assertEquals(1f - 0.66f * 0.6f, Sensibilita.minimo(0.34f, 0.6f), 0.0001f)
        assertEquals(1f + 0.45f * 0.6f, Sensibilita.massimo(1.45f, 0.6f), 0.0001f)
    }
}
