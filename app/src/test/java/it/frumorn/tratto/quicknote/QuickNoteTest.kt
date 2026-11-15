package it.frumorn.tratto.quicknote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuickNoteTest {
    private val roma = ZoneId.of("Europe/Rome")
    private fun ms(anno: Int, mese: Int, giorno: Int, ora: Int, minuto: Int) =
        LocalDateTime.of(anno, mese, giorno, ora, minuto).atZone(roma).toInstant().toEpochMilli()

    @Test
    fun titoloConDataEOraAllItaliana() {
        assertEquals("Note rapide 27 sept., 18:40", QuickNote.titolo(ms(2026, 9, 27, 18, 40), roma, java.util.Locale.FRENCH))
        assertEquals("Note rapide 3 janv., 09:05", QuickNote.titolo(ms(2027, 1, 3, 9, 5), roma, java.util.Locale.FRENCH))
    }

    @Test
    fun foglietto80x55SulTablet() {
        // Tab S6 Lite in orizzontale e in verticale (dp).
        assertEquals(1066.4f to 440f, QuickNote.dimensioni(1333f, 800f).arrotonda())
        assertEquals(640f to 733.15f, QuickNote.dimensioni(800f, 1333f).arrotonda())
    }

    @Test
    fun fogliettoNonTroppoPiccoloMaiFuoriDallaFinestra() {
        // Schermo diviso: resta scrivibile.
        assertEquals(480f to 360f, QuickNote.dimensioni(600f, 400f))
        // Finestra minuscola: non esce dai bordi.
        val (w, h) = QuickNote.dimensioni(400f, 300f)
        assertTrue(w <= 400f && h <= 300f)
    }

    @Test
    fun toccoBreveColDitoChiude() {
        assertTrue(QuickNote.toccoFuoriChiude(conPenna = false, durataMs = 90, spostato = false, piuDita = false, msDallaPenna = 60_000))
    }

    @Test
    fun palmoMentreSiScriveNonChiude() {
        // Penna in hover o appena alzata: il dito fuori dal foglietto e' il palmo.
        assertFalse(QuickNote.toccoFuoriChiude(conPenna = false, durataMs = 90, spostato = false, piuDita = false, msDallaPenna = 200))
        // Palmo appoggiato a lungo.
        assertFalse(QuickNote.toccoFuoriChiude(conPenna = false, durataMs = 2_000, spostato = false, piuDita = false, msDallaPenna = 60_000))
        // Mano che striscia o piu' dita.
        assertFalse(QuickNote.toccoFuoriChiude(conPenna = false, durataMs = 90, spostato = true, piuDita = false, msDallaPenna = 60_000))
        assertFalse(QuickNote.toccoFuoriChiude(conPenna = false, durataMs = 90, spostato = false, piuDita = true, msDallaPenna = 60_000))
    }

    @Test
    fun toccoConLaPennaFuoriChiudeAncheSubitoDopoAverScritto() {
        assertTrue(QuickNote.toccoFuoriChiude(conPenna = true, durataMs = 80, spostato = false, piuDita = false, msDallaPenna = 0))
        // Un tratto iniziato fuori per sbaglio invece no.
        assertFalse(QuickNote.toccoFuoriChiude(conPenna = true, durataMs = 400, spostato = true, piuDita = false, msDallaPenna = 0))
    }

    private fun Pair<Float, Float>.arrotonda() = (Math.round(first * 100) / 100f) to (Math.round(second * 100) / 100f)
}
