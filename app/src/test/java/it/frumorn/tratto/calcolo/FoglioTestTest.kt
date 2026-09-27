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
}
