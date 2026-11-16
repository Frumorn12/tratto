package it.frumorn.tratto.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaTextTest {
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
        for ((letto, atteso) in casi) assertEquals(letto, atteso, FormulaText.normalizza(letto, false))
    }

    @Test
    fun lettereSoloNellaLetturaTollerante() {
        assertNull(FormulaText.normalizza("l2+7", false))
        assertEquals("12+7", FormulaText.normalizza("l2+7", true))
        assertEquals("10", FormulaText.normalizza("1O", true))
        assertEquals("5+2", FormulaText.normalizza("Stz", true))
        assertEquals("10 + 5", FormulaText.normalizza("lO t 5", true))
        assertNull(FormulaText.normalizza("vincoli con", true))
        assertNull(FormulaText.normalizza("Forma standard:", true))
        assertNull(FormulaText.normalizza("", false))
    }

    @Test
    fun varianti() {
        val v = FormulaText.varianti(listOf("l2+7", "12+7"), iniziale = false)
        assertEquals("12+7", v.first())
        // Testo prima della formula: solo per il primo pezzo.
        assertTrue(FormulaText.varianti(listOf("Totale: 12+7"), iniziale = true).contains("12+7"))
        assertTrue(FormulaText.varianti(listOf("Totale:12+7"), iniziale = true).contains("12+7"))
        assertTrue(FormulaText.varianti(listOf("Totale: 12+7"), iniziale = false).isEmpty())
        assertTrue(FormulaText.varianti(listOf("vincoli con"), iniziale = true).isEmpty())
        // Spazi tra le cifre: in fondo, la lettura senza spazi.
        assertEquals("12+7", FormulaText.varianti(listOf("1 2+7"), iniziale = false).last())
    }

    @Test
    fun combinazioniDallaPiuProbabile() {
        val c = Reading.combinazioni(listOf(2, 3))
        assertEquals(6, c.size)
        assertEquals(listOf(0, 0), c[0].toList())
        assertTrue(c.zipWithNext().all { (a, b) -> a.sum() <= b.sum() })
        assertEquals(1, Reading.combinazioni(emptyList()).size)
        assertTrue(Reading.combinazioni(listOf(12, 12, 12, 12)).size <= 64)
    }
}
