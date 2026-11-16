package it.frumorn.tratto.googledocs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dove vanno i disegni nel documento e quali si rifanno. */
class DrawingPlanTest {
    /** Tre paragrafi sulla pagina 0 (cime 121, 200, 300) e uno sulla pagina 1 (cima 121); 1 punto = 2 unita'. */
    private val posti = PostiParagrafi(listOf(0, 0, 0, 1), listOf(121f, 200f, 300f, 121f), 2.0, 72.0)

    private fun modello(posizionati: Map<String, PosizionatoDoc> = emptyMap(), ancorati: Map<Int, List<String>> = emptyMap()) = DocModel(
        "r1", "doc", 500.0, 700.0, 60.0, 60.0, 72.0, 72.0,
        (0 until 4).map { k -> ParagrafoDoc(1 + k * 10, 11 + k * 10, emptyList(), posizionati = ancorati[k].orEmpty()) },
        emptyMap(), posizionati,
    )

    private fun disegno(chiave: String, pagina: Int, x: Float, y: Float, impronta: String = "i") =
        DisegnoVoluto(chiave, "p$pagina", pagina, Riquadro(x, y, x + 100f, y + 40f), impronta)

    @Test
    fun ancoraAlParagrafoSopra() {
        val p = DrawingPlan(modello(), posti, emptyList(), listOf(disegno("a", 0, 244f, 250f)))
        val a = p.daAggiungere.single().second
        assertEquals(1, a.paragrafo)
        // Dal margine sinistro (72 punti = 144 unita') e dalla cima del paragrafo, in punti.
        assertEquals(50.0, a.sinistra, 0.001)
        assertEquals(25.0, a.alto, 0.001)
        assertEquals(50.0, a.larghezza, 0.001)
        assertEquals(20.0, a.altezza, 0.001)
    }

    @Test
    fun sopraIlPrimoParagrafoDellaPagina() {
        // Nel margine in alto: si ancora al primo paragrafo della pagina, piu' in su.
        val a = DrawingPlan(modello(), posti, emptyList(), listOf(disegno("a", 1, 200f, 40f))).daAggiungere.single().second
        assertEquals(3, a.paragrafo)
        assertEquals(-40.5, a.alto, 0.001)
    }

    @Test
    fun paginaSenzaTesto() {
        val p = DrawingPlan(modello(), posti, emptyList(), listOf(disegno("a", 2, 200f, 40f)))
        assertTrue(p.daAggiungere.isEmpty())
        assertEquals(1, p.senzaPosto.size)
    }

    @Test
    fun cheCosaResta() {
        val v = disegno("a", 0, 244f, 250f)
        val a = DrawingPlan(modello(), posti, emptyList(), listOf(v)).daAggiungere.single().second
        val nelDoc = PosizionatoDoc(50.0, 20.0, null, a.sinistra, a.alto, "IN_FRONT_OF_TEXT")
        val salvato = DisegnoSalvato("a", "p0", "kix.1", "i")
        // Uguale e al suo posto: resta.
        val fermo = DrawingPlan(modello(mapOf("kix.1" to nelDoc), mapOf(1 to listOf("kix.1"))), posti, listOf(salvato), listOf(v))
        assertTrue(fermo.vuoto)
        assertEquals(listOf(salvato), fermo.tenuti)
        // Disegno cambiato: si mette il nuovo e si toglie il vecchio.
        val cambiato = DrawingPlan(modello(mapOf("kix.1" to nelDoc), mapOf(1 to listOf("kix.1"))), posti, listOf(salvato), listOf(v.copy(impronta = "j")))
        assertEquals(1, cambiato.daAggiungere.size)
        assertEquals(listOf("kix.1"), cambiato.daTogliere)
        // Spostato in Docs su un altro paragrafo: si rimette al suo posto.
        val spostato = DrawingPlan(modello(mapOf("kix.1" to nelDoc), mapOf(2 to listOf("kix.1"))), posti, listOf(salvato), listOf(v))
        assertEquals(listOf("kix.1"), spostato.daTogliere)
        // Cancellato sul foglio: si toglie.
        val tolto = DrawingPlan(modello(mapOf("kix.1" to nelDoc), mapOf(1 to listOf("kix.1"))), posti, listOf(salvato), emptyList())
        assertEquals(listOf("kix.1"), tolto.daTogliere)
        // Cancellato in Docs: si rimette (ma non si prova a togliere quello che non c'e').
        val sparito = DrawingPlan(modello(), posti, listOf(salvato), listOf(v))
        assertEquals(1, sparito.daAggiungere.size)
        assertTrue(sparito.daTogliere.isEmpty())
    }
}
