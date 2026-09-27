package it.frumorn.tratto.calcolo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class EspressioneTest {
    /** Come in app: testo letto da ML Kit -> testo canonico -> albero -> valore scritto all'italiana. */
    private fun calcola(letto: String): String? {
        val canonico = Testo.normalizza(letto, false) ?: return null
        val nodo = Espressione.analizza(canonico) ?: return null
        return nodo.valuta()?.let { Numero.formatta(it) }
    }

    @Test
    fun operazioni() {
        val casi = listOf(
            "12+7" to "19",
            "12 + 7" to "19",
            "7-10" to "-3",
            "7−10" to "-3",
            "3x(4+5)" to "27",
            "3×(4+5)" to "27",
            "3*(4+5)" to "27",
            "2(3+4)" to "14",
            "(1+2)(3+4)" to "21",
            "(1+2)3" to "9",
            "2+3x4" to "14",
            "12:4:3" to "1",
            "12÷4" to "3",
            "2x3x4" to "24",
            "10/4" to "2,5",
            "1/3" to "0,333333",
            "2/3" to "0,666667",
            "0,1+0,2" to "0,3",
            "0.1+0.2" to "0,3",
            "3,5x2" to "7",
            "1,5-1,5" to "0",
            "1234,5678+0" to "1234,5678",
            "-5+2" to "-3",
            "-(2+3)" to "-5",
            "4--2" to "6",
            "[2+3]x{4}" to "20",
        )
        for ((letto, atteso) in casi) assertEquals(letto, atteso, calcola(letto))
    }

    @Test
    fun potenzeERadici() {
        val casi = listOf(
            "2^10" to "1024",
            "2^-1" to "0,5",
            "-2^2" to "-4",
            "(-2)^2" to "4",
            "(-2)^3" to "-8",
            "2^2^3" to "256",
            "2³" to "8",
            "10²+1" to "101",
            "(2/3)^2" to "0,444444",
            "0^0" to "1",
            "√16" to "4",
            "√(9:4)" to "1,5",
            "2√9" to "6",
            "√2" to "1,414214",
            "(√2)^2" to "2",
            "√2x√2-2" to "0",
            "8^(1/3)" to "2",
            "(-8)^(1/3)" to "-2",
            "2^0,5" to "1,414214",
            "2^(1/2)" to "1,414214",
            "π" to "3,141593",
            "2π" to "6,283185",
            "pi x 2" to "6,283185",
            "50%" to "0,5",
            "200x10%" to "20",
        )
        for ((letto, atteso) in casi) assertEquals(letto, atteso, calcola(letto))
    }

    @Test
    fun impossibili() {
        for (letto in listOf("5:0", "5/(3-3)", "0^-1", "√(-4)", "(-4)^(1/2)", "(-4)^0,5")) {
            assertNull(letto, calcola(letto))
        }
    }

    @Test
    fun nonEspressioni() {
        for (letto in listOf("2 3", "2*+3", "(1+2", "1+2)", "+", "3+", "x3", "1..2", "2..", "()", "√", "e", "vincoli con", "1e5", "5=3")) {
            val canonico = Testo.normalizza(letto, false)
            assertNull(letto, canonico?.let { Espressione.analizza(it) })
        }
    }

    @Test
    fun serveUnOperazione() {
        assertFalse(Espressione.analizza("5")!!.operazione)
        assertFalse(Espressione.analizza("-5")!!.operazione)
        assertFalse(Espressione.analizza("(5)")!!.operazione)
        assertTrue(Espressione.analizza("-5+1")!!.operazione)
        assertTrue(Espressione.analizza("√4")!!.operazione)
        assertTrue(Espressione.analizza("2^(1)")!!.operazione)
    }

    @Test
    fun numeriGrandiEPiccoli() {
        val casi = listOf(
            "999999999999+0" to "999999999999",
            "123456789x1000" to "123456789000",
            "1000000x1000000" to "1e12",
            "2^40" to "1,099512e12",
            "99^99" to "3,697296e197",
            "1/3000000" to "3,333333e-7",
            "0,000123456789x1" to "0,000123457",
            "0,0000015x1" to "0,0000015",
            "999999,9999999+0" to "1000000",
            "10^-7" to "1e-7",
            "-2^40" to "-1,099512e12",
            "2^100000" to "",
        )
        for ((letto, atteso) in casi) {
            if (atteso.isEmpty()) assertNull(letto, calcola(letto)) else assertEquals(letto, atteso, calcola(letto))
        }
    }

    @Test
    fun formattazione() {
        assertEquals("0", Numero.formatta(BigDecimal("-0.0000000")))
        assertEquals("-1,5", Numero.formatta(BigDecimal("-1.5")))
        assertEquals("1,234568", Numero.formatta(BigDecimal("1.2345675")))
        assertEquals("100", Numero.formatta(BigDecimal("99.9999999")))
        assertEquals("999999000000,4", Numero.formatta(BigDecimal("999999000000.4")))
        assertEquals("0", Numero.formatta(Numero.Approssimato(1e-15)))
        assertEquals("2", Numero.formatta(Numero.Approssimato(2.0000000000000004)))
    }

    @Test
    fun trascrizione() {
        val casi = listOf(
            "3*(4+5)" to "3×(4+5)",
            "((1+2)⁄(3))+1" to "(1+2)/3+1",
            "((1)⁄(2))+((1)⁄(3))" to "1/2+1/3",
            "((((1)⁄(2)))⁄(4))" to "(1/2)/4",
            "2^(10)" to "2^10",
            "3+2^(2)*5" to "3+2^2×5",
            "(√(16+9))" to "√(16+9)",
            "7-(2-1)" to "7−(2−1)",
            "8/(2*2)" to "8:(2×2)",
            "8/2*2" to "8:2×2",
            "3*-2" to "3×(−2)",
            "(1+2)^2" to "(1+2)^2",
            "2^(1+1)" to "2^(1+1)",
            "2.5+π" to "2,5+π",
            "50%" to "50%",
        )
        for ((canonico, atteso) in casi) assertEquals(canonico, atteso, Espressione.analizza(canonico)!!.stampa())
    }
}
