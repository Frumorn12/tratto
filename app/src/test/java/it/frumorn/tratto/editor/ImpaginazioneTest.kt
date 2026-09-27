package it.frumorn.tratto.editor

import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.TestoRicco
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dove vanno gli span dei paragrafi e come si numerano gli elenchi (la parte senza Android). */
class ImpaginazioneTest {

    private fun p(testo: String, elenco: Elenco = Elenco.NESSUNO, rientro: Int = 0) =
        Paragrafo(if (testo.isEmpty()) emptyList() else listOf(Frammento(testo)), elenco = elenco, rientro = rientro)

    @Test
    fun ogniParagrafoCopreIlSuoACapo() {
        val t = TestoRicco(listOf(p("abc"), p(""), p("def")))
        val r = Impaginazione.spanParagrafi(t, t.lunghezza).map { it.first.first to it.first.last }
        // "abc\n\ndef": il paragrafo vuoto in mezzo ha il suo "a capo" e non tocca i vicini.
        assertEquals(listOf(0 to 4, 4 to 5, 5 to 8), r)
    }

    @Test
    fun ilParagrafoPrimaDellUltimoVuotoNonLoTocca() {
        val t = TestoRicco(listOf(p("abc"), p("")))
        val r = Impaginazione.spanParagrafi(t, t.lunghezza).map { it.first.first to it.first.last }
        // "abc\n": lo span di "abc" si ferma prima dell'a capo, quello vuoto sta in fondo.
        assertEquals(listOf(0 to 3, 4 to 4), r)
    }

    @Test
    fun elenchiNumeratiPerLivello() {
        val t = TestoRicco(
            listOf(
                p("uno", Elenco.NUMERATO), p("due", Elenco.NUMERATO),
                p("a", Elenco.NUMERATO, rientro = 1), p("b", Elenco.NUMERATO, rientro = 1),
                p("tre", Elenco.NUMERATO),
                p("punto", Elenco.PUNTATO), p("quattro", Elenco.NUMERATO),
                p("fuori"), p("di nuovo", Elenco.NUMERATO),
            ),
        )
        val numeri = Impaginazione.spanParagrafi(t, t.lunghezza).map { it.second.numero }
        assertEquals(listOf(1, 2, 1, 2, 3, 0, 4, 0, 1), numeri)
    }
}
