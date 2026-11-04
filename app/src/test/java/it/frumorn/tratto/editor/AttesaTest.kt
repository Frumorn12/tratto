package it.frumorn.tratto.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Il polling di Google Doc quando scrivo su Tratto è lento" (Andrea, 04/11). Chi scrive a mano senza
 * fermarsi fa un tratto ogni pochi decimi di secondo: con un'attesa che riparte a ogni tratto la nota
 * non si salvava (e il documento non si aggiornava) finche' non ci si fermava. Ora l'attesa ha un tetto.
 */
class AttesaTest {
    @Test
    fun primaModificaAspettaLaPausa() {
        assertEquals(1000L, Attesa.ritardo(primaModifica = 5000L, ora = 5000L, pausa = 1000L, massimo = 2000L))
    }

    @Test
    fun scrivendoDiFilaNonSiAspettaOltreIlTetto() {
        // Tratti ogni 300 ms: l'attesa si accorcia e scade 2 secondi dopo il primo tratto non salvato.
        assertEquals(1000L, Attesa.ritardo(5000L, 5900L, 1000L, 2000L))
        assertEquals(500L, Attesa.ritardo(5000L, 6500L, 1000L, 2000L))
        assertEquals(100L, Attesa.ritardo(5000L, 6900L, 1000L, 2000L))
    }

    @Test
    fun tettoGiaPassato() {
        assertEquals(0L, Attesa.ritardo(5000L, 7500L, 1000L, 2000L))
    }
}
