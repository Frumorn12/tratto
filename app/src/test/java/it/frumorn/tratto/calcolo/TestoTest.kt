package it.frumorn.tratto.calcolo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestoTest {
    @Test
    fun letturaNormale() {
        val casi = listOf(
            "12 + 7" to "12 + 7",
            "3×(4+5)" to "3*(4+5)",
            "3x(4+5)" to "3*(4+5)",
            "6÷3" to "6/3",
            "6:3" to "6/3",
            "3,5" to "3.5",
            "7−2" to "7-2",
            "7–2" to "7-2",
            "2³" to "2^(3)",
            "2¹⁰" to "2^(10)",
            "pi" to "π",
            "2·3" to "2*3",
            "√9" to "√9",
            "[1+2]" to "(1+2)",
            "12+7=" to "12+7",
            "  4  +  4 " to "4 + 4",
        )
        for ((letto, atteso) in casi) assertEquals(letto, atteso, Testo.normalizza(letto, false))
    }

    @Test
    fun lettereSoloNellaLetturaTollerante() {
        assertNull(Testo.normalizza("l2+7", false))
        assertEquals("12+7", Testo.normalizza("l2+7", true))
        assertEquals("10", Testo.normalizza("1O", true))
        assertEquals("5+2", Testo.normalizza("Stz", true))
        assertEquals("10 + 5", Testo.normalizza("lO t 5", true))
        assertNull(Testo.normalizza("vincoli con", true))
        assertNull(Testo.normalizza("Forma standard:", true))
        assertNull(Testo.normalizza("", false))
    }

    @Test
    fun varianti() {
        val v = Testo.varianti(listOf("l2+7", "12+7"), iniziale = false)
        assertEquals("12+7", v.first())
        // Testo prima della formula: solo per il primo pezzo.
        assertTrue(Testo.varianti(listOf("Totale: 12+7"), iniziale = true).contains("12+7"))
        assertTrue(Testo.varianti(listOf("Totale:12+7"), iniziale = true).contains("12+7"))
        assertTrue(Testo.varianti(listOf("Totale: 12+7"), iniziale = false).isEmpty())
        assertTrue(Testo.varianti(listOf("vincoli con"), iniziale = true).isEmpty())
        // Spazi tra le cifre: in fondo, la lettura senza spazi.
        assertEquals("12+7", Testo.varianti(listOf("1 2+7"), iniziale = false).last())
    }

    @Test
    fun combinazioniDallaPiuProbabile() {
        val c = Lettura.combinazioni(listOf(2, 3))
        assertEquals(6, c.size)
        assertEquals(listOf(0, 0), c[0].toList())
        assertTrue(c.zipWithNext().all { (a, b) -> a.sum() <= b.sum() })
        assertEquals(1, Lettura.combinazioni(emptyList()).size)
        assertTrue(Lettura.combinazioni(listOf(12, 12, 12, 12)).size <= 64)
    }
}
