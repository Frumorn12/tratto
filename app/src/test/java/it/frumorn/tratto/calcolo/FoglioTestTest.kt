package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.FormatoPagina
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Un foglio di prova scritto sul tablet con la S Pen: 10/2, 10³, 20⁵, 50⁵, 9³, 3⁹ e 20/5, con gli
 * uguale piccoli, esponenti scritti in due tratti (3 e 5) o grandi quasi come le cifre, e il
 * numeratore 20 con le cifre staccate. Si controlla la struttura trovata: il resto lo fa ML Kit.
 */
class FoglioTestTest {
    @Before
    fun pulisci() = Calcolatore.azzera()

    @Test
    fun strutturaDiOgniFormula() {
        val tutti = FormatoPagina.leggi(File("src/test/resources/calcolo/nota-test-esponenti.tp")).filter { !Calcolatore.risultato(it) }
        val indice = tutti.withIndex().associate { (i, t) -> t.id to i }
        val pagina = ArrayList<Tratto>()
        var adesso = 10_000L
        val trovate = LinkedHashMap<Int, String>()
        for ((i, t) in tutti.withIndex()) {
            pagina += t
            adesso += 400
            val r = Calcolatore.trova(pagina, listOf(t), adesso) ?: continue
            val riga = Struttura.analizza(r.forme, r.h)
            if (Struttura.ambigua(riga)) { trovate[i] = "ambigua"; continue }
            val schema = Lettura.schema(riga)
            // Lo schema con, al posto di ogni pezzo, i tratti di ogni simbolo.
            trovate[i] = schema.parti.joinToString("") { p ->
                when (p) {
                    is Lettura.Fissa -> p.testo
                    is Lettura.Buco -> schema.sequenze[p.sequenza].gruppi.joinToString(" ") { g -> g.forme.mapNotNull { indice[it.id] }.sorted().joinToString("+") }
                }
            }
        }
        assertEquals(
            mapOf(
                5 to "((0 1)⁄(3))",        // 10/2
                11 to "6 7^(8+9)",         // 10³ con il 3 in due tratti
                17 to "12 13^(14+15)",     // 20⁵ con lo 0 piccolo
                24 to "18+19 20^(21+22)",  // 50⁵
                29 to "25+26^(27)",        // 9³
                33 to "30^(31)",           // 3⁹
                40 to "((34 35)⁄(37+38))", // 20/5
            ),
            trovate,
        )
    }

    @Test
    fun esponenteDopoUnaParentesi() {
        // "20 + (3-1)³ =" scritto sul tablet: il 3 all'esponente, grande quasi come le cifre e in
        // alto dopo la parentesi, restava fuori (risultato 19 invece di 28).
        val tutti = FormatoPagina.leggi(File("src/test/resources/calcolo/parentesi-alla-terza.tp"))
        val indice = tutti.withIndex().associate { (i, t) -> t.id to i }
        val pagina = ArrayList<Tratto>()
        var adesso = 10_000L
        var trovata: String? = null
        for (t in tutti) {
            pagina += t
            adesso += 400
            val r = Calcolatore.trova(pagina, listOf(t), adesso) ?: continue
            val schema = Lettura.schema(Struttura.analizza(r.forme, r.h))
            trovata = schema.parti.joinToString("") { p ->
                when (p) {
                    is Lettura.Fissa -> p.testo
                    is Lettura.Buco -> schema.sequenze[p.sequenza].gruppi.joinToString(" ") { g -> g.forme.mapNotNull { indice[it.id] }.sorted().joinToString("+") }
                }
            }
        }
        assertEquals("0 1 2+3 4 5 6 7 8^(9)", trovata)
    }

    @Test
    fun unoStrettoNonDiventaQuattro() {
        // Nella stessa formula ML Kit aveva messo "20+(3-4)" prima di "20+(3-1)".
        val tutti = FormatoPagina.leggi(File("src/test/resources/calcolo/parentesi-alla-terza.tp"))
        val pagina = ArrayList<Tratto>()
        var adesso = 10_000L
        var r: Raccolta? = null
        for (t in tutti) {
            pagina += t
            adesso += 400
            Calcolatore.trova(pagina, listOf(t), adesso)?.let { r = it }
        }
        val schema = Lettura.schema(Struttura.analizza(r!!.forme, r!!.h))
        val base = schema.sequenze.first()
        assertEquals(listOf("20+(3-1)", "20+(3-4)"), Lettura.coerenti(base, listOf("20+(3-4)", "20+(3-1)")))
    }

    @Test
    fun dueAllaCentoventotto() {
        // Due volte "2¹²⁸ =" scritto sul tablet. Nel primo l'esponente sta tutto sopra la cima del
        // 2 ed e' alto quasi come lui (e l'1 finiva fuso con il 2); nel secondo la punta dell'1
        // sfiorava la fascia dell'asse e l'8 restava fuori (2¹² = 4096).
        val tutti = FormatoPagina.leggi(File("src/test/resources/calcolo/due-alla-128.tp"))
        val indice = tutti.withIndex().associate { (i, t) -> t.id to i }
        val pagina = ArrayList<Tratto>()
        var adesso = 10_000L
        val trovate = ArrayList<String>()
        for (t in tutti) {
            pagina += t
            adesso += 400
            val r = Calcolatore.trova(pagina, listOf(t), adesso) ?: continue
            val schema = Lettura.schema(Struttura.analizza(r.forme, r.h))
            trovate += schema.parti.joinToString("") { p ->
                when (p) {
                    is Lettura.Fissa -> p.testo
                    is Lettura.Buco -> schema.sequenze[p.sequenza].gruppi.joinToString(" ") { g -> g.forme.mapNotNull { indice[it.id] }.sorted().joinToString("+") }
                }
            }
        }
        assertEquals(listOf("0^(1+2 3 4)", "7^(8 9 10)"), trovate)
    }
}
