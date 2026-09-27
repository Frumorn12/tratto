package it.frumorn.tratto.scrittura

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RigheTest {
    private var id = 0L

    /** Tratto verticale da (x, alto) a (x, basso), con qualche punto in mezzo. */
    private fun asta(x: Float, alto: Float, basso: Float, larghezza: Float = 8f): Tratto {
        val n = 6
        val p = FloatArray(n * Tratto.CAMPI)
        for (i in 0 until n) {
            val u = i / (n - 1f)
            val o = i * Tratto.CAMPI
            p[o] = x + larghezza * u
            p[o + 1] = alto + (basso - alto) * u
            p[o + 2] = 0.5f
            p[o + 3] = -1f
            p[o + 4] = -1f
            p[o + 5] = i * 8f
        }
        return Tratto(id++, Penna.PENNA, 0, 2f, p)
    }

    /**
     * Una riga di "lettere" con base [base] e altezza x 15: corpi, aste che salgono di 12,
     * code che scendono di 12, un puntino sopra e una virgola sotto.
     */
    private fun riga(base: Float, x0: Float = 50f): List<Tratto> = listOf(
        asta(x0 + 40f, base - 15f, base),            // a
        asta(x0 + 60f, base - 27f, base),            // l (asta)
        asta(x0 + 80f, base - 15f, base + 12f),      // g (coda)
        asta(x0 + 100f, base - 15f, base),           // o
        asta(x0 + 120f, base - 15f, base),           // i
        asta(x0 + 121f, base - 22f, base - 20f, 2f), // puntino della i
        asta(x0 + 140f, base - 27f, base),           // d
        asta(x0 + 160f, base - 2f, base + 5f, 2f),   // virgola
        asta(x0, base - 15f, base),                  // prima lettera, scritta per ultima
    )

    @Test
    fun righeConCodeEAsteCheSiToccano() {
        // Interlinea 40 con aste e code di 12: la coda della g di una riga arriva quasi all'asta
        // della l della riga sotto, e gli ingombri delle righe si sovrappongono.
        val r1 = riga(100f)
        val r2 = riga(140f)
        val r3 = riga(180f)
        val tutti = (r3 + r1 + r2).shuffled(java.util.Random(7))
        val righe = Righe.dividi(tutti)
        assertEquals(3, righe.size)
        assertEquals(r1.map { it.id }.toSet(), righe[0].tratti.map { it.id }.toSet())
        assertEquals(r2.map { it.id }.toSet(), righe[1].tratti.map { it.id }.toSet())
        assertEquals(r3.map { it.id }.toSet(), righe[2].tratti.map { it.id }.toSet())
    }

    @Test
    fun dentroLaRigaDaSinistraADestra() {
        val righe = Righe.dividi(riga(100f))
        assertEquals(1, righe.size)
        val sinistre = righe[0].tratti.map { t -> (0 until t.quanti).minOf { t.punti[it * Tratto.CAMPI] } }
        assertEquals(sinistre.sorted(), sinistre)
    }

    @Test
    fun puntiniEVirgoleVannoAllaLoroRiga() {
        val r1 = riga(100f)
        val r2 = riga(140f)
        val righe = Righe.dividi(r1 + r2)
        assertEquals(2, righe.size)
        // Il puntino della i della seconda riga (y ~ 119) sta tra la coda della g della prima
        // riga (fino a 112) e il corpo della seconda (da 125): va con la seconda.
        assertTrue(righe[1].tratti.any { it.id == r2[5].id })
        assertTrue(righe[0].tratti.any { it.id == r1[7].id })
    }

    @Test
    fun casiLimite() {
        assertEquals(0, Righe.dividi(emptyList()).size)
        // Solo tratti piatti: nessun tratto "principale" per altezza, ma una riga comunque.
        val piatti = listOf(asta(0f, 50f, 50f, 20f), asta(40f, 50f, 50f, 20f))
        assertEquals(1, Righe.dividi(piatti).size)
    }
}
