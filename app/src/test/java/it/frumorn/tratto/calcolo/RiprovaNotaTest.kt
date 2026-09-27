package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.FormatoPagina
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tratti veri scritti sul tablet in una nota rapida: "2+3 =", "4+5=", "10+(3+5)=", "10+(3-1) ="
 * e "10+9    =". Gli ultimi due non davano risultato: l'uguale era piccolo (barre di 7-13 unita'
 * dopo cifre alte 27-37), con la seconda barra inclinata di 25 gradi o sfalsata, e staccato dalla
 * formula di piu' di un'altezza di cifra.
 */
class RiprovaNotaTest {
    @Before
    fun pulisci() = Calcolatore.azzera()

    @Test
    fun ogniUgualeRaccoglieLaSuaFormula() {
        val tutti = FormatoPagina.leggi(File("src/test/resources/calcolo/nota-rapida-uguale-staccato.tp"))
        val pagina = ArrayList<Tratto>()
        var adesso = 10_000L
        val trovati = LinkedHashMap<Int, Int>()
        for ((i, t) in tutti.withIndex()) {
            pagina += t
            adesso += 400
            Calcolatore.trova(pagina, listOf(t), adesso)?.let { trovati[i] = it.forme.size }
        }
        // Tratto che chiude l'uguale -> tratti della formula raccolti a sinistra.
        assertEquals(mapOf(5 to 4, 13 to 5, 36 to 11, 40 to 9, 47 to 5), trovati)
    }
}
