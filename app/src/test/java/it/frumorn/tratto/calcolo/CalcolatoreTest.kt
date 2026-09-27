package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.scrittura.Stile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class CalcolatoreTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun carica() = Quaderno.preparaFont()

        const val BASE = 300f
    }

    private var adesso = 10_000L

    @Before
    fun pulisci() = Calcolatore.azzera()

    /** Aggiunge [uguale] alla pagina, un trattino alla volta, e chiede il risultato. */
    private fun chiudi(q: Quaderno, uguale: List<Tratto>, lettore: Lettore = LettoreFinto(q), pausa: Long = 400L): Risultato? = runBlocking {
        var r: Risultato? = null
        for (t in uguale) {
            q.tratti += t
            adesso += pausa
            r = Calcolatore.dopoTratto(q.tratti, listOf(t), q.stile, { q.nuovoId() }, lettore, adesso)
        }
        r
    }

    private fun Tratto.ys() = (0 until quanti).map { punti[it * Tratto.CAMPI + 1] }
    private fun Tratto.xs() = (0 until quanti).map { punti[it * Tratto.CAMPI] }

    private fun formula(q: Quaderno, testo: String, x: Float = 100f): Pair<Float, List<Tratto>> {
        val fine = q.scrivi(testo, x, BASE)
        return fine to q.uguale(fine + 0.3f * q.hCifre, q.asse(BASE))
    }

    @Test
    fun sommaSemplice() {
        for (stile in Stile.entries) {
            Calcolatore.azzera()
            val q = Quaderno(stile)
            val (_, uguale) = formula(q, "12+7")
            val lettore = LettoreFinto(q)
            val r = chiudi(q, uguale, lettore)
            assertNotNull("$stile: nessun risultato", r)
            r!!
            assertEquals("19", r.valore)
            assertEquals("12+7", r.espressione)
            assertEquals(listOf("12+7"), lettore.letti)
            // A destra dell'uguale, cifre alte come quelle scritte, sulla stessa linea di base.
            val destraUguale = uguale.maxOf { t -> t.xs().max() }
            assertTrue(r.tratti.isNotEmpty())
            assertTrue(r.tratti.all { t -> t.xs().min() > destraUguale })
            val alto = r.tratti.minOf { t -> t.ys().min() }
            val basso = r.tratti.maxOf { t -> t.ys().max() }
            assertEquals("$stile: base", BASE, basso, 0.12f * q.hCifre)
            assertEquals("$stile: altezza", q.hCifre, basso - alto, 0.15f * q.hCifre)
            // Colore, penna e spessore dell'uguale.
            assertTrue(r.tratti.all { it.colore == uguale.last().colore && it.penna == uguale.last().penna && it.spessore == uguale.last().spessore })
        }
    }

    @Test
    fun perEParentesi() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "3x(4+5)")
        val r = chiudi(q, uguale)!!
        assertEquals("27", r.valore)
        assertEquals("3×(4+5)", r.espressione)
    }

    @Test
    fun decimaliEDivisione() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "7:2")
        assertEquals("3,5", chiudi(q, uguale)!!.valore)
    }

    @Test
    fun divisionePerZeroNonScriveNiente() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "5:0")
        assertNull(chiudi(q, uguale))
    }

    @Test
    fun frazioneConLaBarra() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        // (1+2) sopra la barra, 3 sotto, poi "+1".
        val x0 = 100f
        val larghezza = q.scrivi("1+2", x0 + 0.15f * h, asse - 0.25f * h) + 0.15f * h - x0
        q.tratto(x0, asse, x0 + larghezza, asse + 0.5f)
        q.scrivi("3", x0 + larghezza / 2f - 0.35f * h, asse + 0.3f * h + h)
        val fine = q.scrivi("+1", x0 + larghezza + 0.3f * h, BASE)
        val uguale = q.uguale(fine + 0.3f * h, asse)

        val raccolta = Calcolatore.trova(q.tratti + uguale, uguale, 0L)
        assertNotNull(raccolta)
        val riga = Struttura.analizza(raccolta!!.forme, raccolta.h)
        val frazione = riga.voci.map { it.elemento }.filterIsInstance<Frazione>().single()
        assertEquals(3, frazione.numeratore.voci.size)
        assertEquals(1, frazione.denominatore.voci.size)

        Calcolatore.azzera()
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("2", r.valore)
        assertEquals("(1+2)/3+1", r.espressione)
        assertEquals(listOf("1+2", "3", "+1"), lettore.letti)
    }

    @Test
    fun soloFrazioniSullaRigaPrincipale() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        var x = 100f
        for ((n, d) in listOf("1" to "2", "1" to "3")) {
            q.scrivi(n, x + 0.3f * h, asse - 0.25f * h)
            q.tratto(x, asse, x + 1.1f * h, asse)
            q.scrivi(d, x + 0.3f * h, asse + 0.3f * h + h)
            x += 1.1f * h
            if (n == "1" && d == "2") x = q.scrivi("+", x + 0.2f * h, BASE) + 0.1f * h
        }
        val uguale = q.uguale(x + 0.3f * h, asse)
        val r = chiudi(q, uguale)!!
        assertEquals("0,833333", r.valore)
        assertEquals("1/2+1/3", r.espressione)
        // Senza cifre sulla riga principale il risultato si centra sull'asse.
        val alto = r.tratti.minOf { t -> t.ys().min() }
        val basso = r.tratti.maxOf { t -> t.ys().max() }
        assertTrue(alto < asse && basso > asse)
    }

    @Test
    fun frazioneDiFrazioni() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        // (1/2) / 4: barra interna corta in alto, barra principale lunga sull'asse.
        val x0 = 100f
        val sopra = asse - 0.3f * h - h - 0.3f * h
        q.scrivi("1", x0 + 0.6f * h, sopra - 0.25f * h)
        q.tratto(x0 + 0.3f * h, sopra, x0 + 1.5f * h, sopra)
        q.scrivi("2", x0 + 0.6f * h, sopra + 0.3f * h + h)
        q.tratto(x0, asse, x0 + 1.8f * h, asse)
        q.scrivi("4", x0 + 0.6f * h, asse + 0.3f * h + h)
        val uguale = q.uguale(x0 + 2.2f * h, asse)
        val r = chiudi(q, uguale)!!
        assertEquals("0,125", r.valore)
        assertEquals("(1/2)/4", r.espressione)
    }

    @Test
    fun esponente() {
        val q = Quaderno()
        val h = q.hCifre
        val fine2 = q.scrivi("2", 100f, BASE)
        val fine = q.scrivi("10", fine2 + 0.05f * h, BASE - 0.55f * h, scala = 0.6f)
        val uguale = q.uguale(fine + 0.3f * h, q.asse(BASE))
        val raccolta = Calcolatore.trova(q.tratti + uguale, uguale, 0L)!!
        val riga = Struttura.analizza(raccolta.forme, raccolta.h)
        assertEquals(1, riga.voci.size)
        assertEquals(2, riga.voci[0].esponente!!.voci.size)

        Calcolatore.azzera()
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("1024", r.valore)
        assertEquals("2^10", r.espressione)
        assertEquals(listOf("2", "10"), lettore.letti)
    }

    @Test
    fun esponenteInMezzo() {
        val q = Quaderno()
        val h = q.hCifre
        // 3+2² x 5
        var x = q.scrivi("3+2", 100f, BASE)
        x = q.scrivi("2", x + 0.05f * h, BASE - 0.55f * h, scala = 0.6f)
        x = q.scrivi("x5", x + 0.2f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, q.asse(BASE))
        val r = chiudi(q, uguale)!!
        assertEquals("23", r.valore)
        assertEquals("3+2^2×5", r.espressione)
    }

    @Test
    fun radiceConLaBarra() {
        val q = Quaderno()
        val h = q.hCifre
        val x0 = 100f
        val alto = BASE - h - 0.25f * h
        val fine = q.scrivi("16+9", x0 + 0.8f * h, BASE) + 0.1f * h
        q.tratto(x0, BASE - 0.45f * h, x0 + 0.2f * h, BASE - 0.55f * h, x0 + 0.4f * h, BASE + 0.05f * h, x0 + 0.7f * h, alto, fine, alto)
        val uguale = q.uguale(fine + 0.3f * h, q.asse(BASE))
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("5", r.valore)
        assertEquals("√(16+9)", r.espressione)
        assertEquals(listOf("16+9"), lettore.letti)
    }

    @Test
    fun radiceConLaBarraInclinataEStaccata() {
        for (staccata in listOf(false, true)) {
            Calcolatore.azzera()
            val q = Quaderno()
            val h = q.hCifre
            val x0 = 100f
            val alto = BASE - h - 0.25f * h
            val fine = q.scrivi("9x4", x0 + 0.8f * h, BASE) + 0.1f * h
            if (staccata) {
                // Segno di radice e barra in due tratti; la barra sale un poco verso destra.
                q.tratto(x0, BASE - 0.45f * h, x0 + 0.2f * h, BASE - 0.55f * h, x0 + 0.4f * h, BASE + 0.05f * h, x0 + 0.7f * h, alto)
                q.tratto(x0 + 0.72f * h, alto + 1f, fine, alto - 3f)
            } else {
                q.tratto(x0, BASE - 0.45f * h, x0 + 0.2f * h, BASE - 0.55f * h, x0 + 0.4f * h, BASE + 0.05f * h, x0 + 0.7f * h, alto + 2f, fine, alto - 2f)
            }
            val uguale = q.uguale(fine + 0.3f * h, q.asse(BASE))
            val r = chiudi(q, uguale)
            assertEquals("staccata=$staccata", "6", r?.valore)
            assertEquals("staccata=$staccata", "√(9×4)", r?.espressione)
        }
    }

    @Test
    fun radiceSenzaBarra() {
        val q = Quaderno()
        val h = q.hCifre
        val x0 = 100f
        q.tratto(x0, BASE - 0.45f * h, x0 + 0.1f * h, BASE - 0.5f * h, x0 + 0.3f * h, BASE + 0.05f * h, x0 + 0.75f * h, BASE - 1.15f * h)
        val fine = q.scrivi("81", x0 + 0.8f * h, BASE)
        val uguale = q.uguale(fine + 0.3f * h, q.asse(BASE))
        val r = chiudi(q, uguale)!!
        assertEquals("9", r.valore)
    }

    @Test
    fun divisoConIPuntini() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        var x = q.scrivi("12", 100f, BASE)
        val sx = x + 0.2f * h
        q.tratto(sx, asse, sx + 0.6f * h, asse)
        q.tratto(sx + 0.3f * h, asse - 0.3f * h, sx + 0.32f * h, asse - 0.28f * h)
        q.tratto(sx + 0.3f * h, asse + 0.3f * h, sx + 0.32f * h, asse + 0.32f * h)
        x = q.scrivi("4", sx + 0.8f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, asse)
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("3", r.valore)
        assertEquals(listOf("12", "4"), lettore.letti)
    }

    @Test
    fun puntoPerAMetaAltezza() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        var x = q.scrivi("3", 100f, BASE)
        q.tratto(x + 0.2f * h, asse, x + 0.23f * h, asse + 0.03f * h)
        x = q.scrivi("4", x + 0.45f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, asse)
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("12", r.valore)
        assertEquals(listOf("3", "4"), lettore.letti)
    }

    @Test
    fun evidenziatoreNonFaUguali() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "1+1")
        val evidenziati = uguale.map { Tratto(it.id, Penna.EVIDENZIATORE, it.colore, it.spessore, it.punti) }
        assertNull(chiudi(q, evidenziati))
    }

    @Test
    fun testoConUgualeNonSiCalcola() {
        val q = Quaderno(Stile.CORSIVO)
        val fine = q.parole("Forma standard: vincoli con", 60f, BASE)
        val uguale = q.uguale(fine + 0.4f * q.hCifre, q.asse(BASE))
        val lettore = LettoreFinto(q)
        assertNull(chiudi(q, uguale, lettore))
    }

    @Test
    fun testoPrimaDellaFormula() {
        val q = Quaderno()
        var x = q.parole("Totale:", 60f, BASE)
        x = q.scrivi("12+7", x + 0.5f * q.hCifre, BASE)
        val uguale = q.uguale(x + 0.3f * q.hCifre, q.asse(BASE))
        val r = chiudi(q, uguale)!!
        assertEquals("19", r.valore)
        assertEquals("12+7", r.espressione)
    }

    @Test
    fun letturaSbagliataSiCorreggeConICandidati() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "10+5")
        // ML Kit legge "lO t 5": con la lettura tollerante e' 10+5.
        val lettore = LettoreFinto(q, mapOf("10+5" to listOf("lO t 5", "IO+S")))
        assertEquals("15", chiudi(q, uguale, lettore)!!.valore)
    }

    @Test
    fun scritturaNormaleNonChiamaMlKit() = runBlocking {
        val q = Quaderno(Stile.CORSIVO)
        val lettore = LettoreFinto(q)
        val pagina = ArrayList<Tratto>()
        val parole = Quaderno(Stile.CORSIVO)
        parole.parole("Oggi e' una bella giornata, domani piove", 60f, BASE)
        parole.scrivi("12 + 7 - 3", 60f, BASE + 60f)
        for (t in parole.tratti) {
            pagina += t
            adesso += 300
            assertNull(Calcolatore.dopoTratto(pagina, listOf(t), q.stile, { 0L }, lettore, adesso))
        }
        assertEquals(0, lettore.chiamate)
    }

    @Test
    fun ugualeTroppoLento() {
        val q = Quaderno()
        val (_, uguale) = formula(q, "12+7")
        assertNull(chiudi(q, uguale, pausa = 3000L))
    }

    @Test
    fun ugualeInUnaSolaChiamata() = runBlocking {
        val q = Quaderno()
        val (_, uguale) = formula(q, "6x7")
        q.tratti += uguale
        val r = Calcolatore.dopoTratto(q.tratti, uguale, q.stile, { q.nuovoId() }, LettoreFinto(q), adesso)
        assertEquals("42", r!!.valore)
        // Lo stesso uguale non si calcola due volte.
        assertNull(Calcolatore.dopoTratto(q.tratti, uguale, q.stile, { q.nuovoId() }, LettoreFinto(q), adesso))
    }

    @Test
    fun nonScriveSeADestraCeGiaQualcosa() {
        val q = Quaderno()
        val (fine, uguale) = formula(q, "2+2")
        q.scrivi("4", fine + 0.3f * q.hCifre + uguale.maxOf { t -> t.xs().max() - t.xs().min() } + 0.3f * q.hCifre, BASE)
        assertNull(chiudi(q, uguale))
    }

    @Test
    fun dueFormuleSullaStessaRiga() {
        val q = Quaderno()
        val h = q.hCifre
        // 3+4 = 7 (risultato scritto dal calcolatore), poi subito 2+2 =.
        val (_, primo) = formula(q, "3+4")
        val sette = chiudi(q, primo)!!
        assertEquals("7", sette.valore)
        q.tratti += sette.tratti
        val x = q.scrivi("2+2", sette.tratti.maxOf { t -> t.xs().max() } + 0.8f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, q.asse(BASE))
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("4", r.valore)
        assertEquals(listOf("2+2"), lettore.letti)
    }

    @Test
    fun ugualePrecedenteChiudeLaFormula() {
        val q = Quaderno()
        val h = q.hCifre
        // 3+4=7 tutto a mano, poi 2+2 staccato: si legge solo 2+2.
        var x = q.scrivi("3+4", 100f, BASE)
        q.tratti += q.uguale(x + 0.3f * h, q.asse(BASE))
        x = q.scrivi("7", x + 1.4f * h, BASE)
        x = q.scrivi("2+2", x + 1.8f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, q.asse(BASE))
        val lettore = LettoreFinto(q)
        assertEquals("4", chiudi(q, uguale, lettore)!!.valore)
        assertEquals(listOf("2+2"), lettore.letti)
    }

    @Test
    fun ugualeInUnTratto() {
        val q = Quaderno()
        val h = q.hCifre
        val fine = q.scrivi("9-4", 100f, BASE)
        val asse = q.asse(BASE)
        val x = fine + 0.3f * h
        val w = 0.8f * h
        val zeta = q.trattoFuori(x, asse - 0.18f * h, x + w, asse - 0.18f * h, x + 0.1f * w, asse + 0.18f * h, x + w, asse + 0.18f * h)
        assertTrue(RilevaUguale.unTratto(Forma(zeta)))
        assertEquals("5", chiudi(q, listOf(zeta))!!.valore)
    }

    @Test
    fun ugualeGeometrico() {
        val q = Quaderno()
        val (a, b) = q.uguale(0f, 100f, 20f)
        assertTrue(RilevaUguale.dueTratti(Forma(a), Forma(b)))
        // Un meno e un trattino molto piu' corto, o troppo lontani, o una barra verticale: no.
        val corto = q.trattoFuori(0f, 110f, 6f, 110f)
        assertTrue(!RilevaUguale.dueTratti(Forma(a), Forma(corto)))
        val lontano = q.trattoFuori(0f, 130f, 20f, 130f)
        assertTrue(!RilevaUguale.dueTratti(Forma(a), Forma(lontano)))
        val spostato = q.trattoFuori(40f, 107f, 60f, 107f)
        assertTrue(!RilevaUguale.dueTratti(Forma(a), Forma(spostato)))
        val verticale = q.trattoFuori(10f, 90f, 10f, 110f)
        assertTrue(!RilevaUguale.dueTratti(Forma(a), Forma(verticale)))
    }

    @Test
    fun simboliDaPiuTratti() {
        val q = Quaderno()
        for (stile in Stile.entries) {
            val q = Quaderno(stile)
            q.scrivi("12+7x4", 100f, BASE)
            val forme = q.tratti.map { Forma(it) }
            val gruppi = Struttura.raggruppa(forme, q.hCifre).sortedBy { it.sx }
            // Un gruppo per simbolo, anche per quelli di due tratti (il piu', la x, il 4 e il 7 dello stampatello).
            assertEquals("$stile", "12+7x4".map { it.toString() }, gruppi.map { g -> g.forme.map { q.simboli[it.id]!!.second }.distinct().single() })
            assertTrue(gruppi.any { it.forme.size == 2 })
        }
    }

    @Test
    fun rigaSopraNonEntraNellaFormula() {
        val q = Quaderno()
        val h = q.hCifre
        // Una riga di testo sopra (interlinea del foglio a righe: 38 unita') e la formula sotto.
        q.parole("gatto e cane", 100f, BASE - 38f)
        val fine = q.scrivi("5-3", 100f, BASE)
        val uguale = q.uguale(fine + 0.3f * h, q.asse(BASE))
        val lettore = LettoreFinto(q)
        val r = chiudi(q, uguale, lettore)!!
        assertEquals("2", r.valore)
        assertEquals(listOf("5-3"), lettore.letti)
    }

    @Test
    fun scritturaIrregolare() {
        val casi = listOf(
            "12+7" to "19",
            "3x(4+5)" to "27",
            "100-58" to "42",
            "7:2" to "3,5",
            "2,5x4" to "10",
            "(1+2)x(3+4)" to "21",
            "9-4-3" to "2",
        )
        val sbagliati = ArrayList<String>()
        for (stile in Stile.entries) for (seme in 1..15) for ((testo, atteso) in casi) {
            Calcolatore.azzera()
            val q = Quaderno(stile).apply { caso = java.util.Random(seme * 31L + testo.hashCode()) }
            val r = java.util.Random(seme.toLong())
            val fine = q.scrivi(testo, 100f, BASE)
            val h = q.hCifre
            // Uguale piu' o meno largo, distante e inclinato.
            val x = fine + (0.15f + 0.5f * r.nextFloat()) * h
            val w = (0.6f + 0.5f * r.nextFloat()) * h
            val asse = q.asse(BASE) + (r.nextFloat() - 0.5f) * 0.2f * h
            val d = (0.12f + 0.12f * r.nextFloat()) * h
            val a = q.trattoFuori(x, asse - d, x + w, asse - d + (r.nextFloat() - 0.5f) * 0.2f * w)
            val b = q.trattoFuori(x + 0.1f * w * r.nextFloat(), asse + d, x + w * (0.8f + 0.3f * r.nextFloat()), asse + d + (r.nextFloat() - 0.5f) * 0.2f * w)
            val lettore = LettoreFinto(q)
            val esito = chiudi(q, listOf(a, b), lettore)
            if (esito?.valore != atteso) sbagliati += "$stile $seme $testo -> ${esito?.valore} ${lettore.letti}"
        }
        assertTrue(sbagliati.joinToString("\n"), sbagliati.isEmpty())
    }

    @Test
    fun esponenteDopoLaParentesi() {
        for (stile in Stile.entries) {
            Calcolatore.azzera()
            val q = Quaderno(stile)
            val h = q.hCifre
            var x = q.scrivi("(1+2)", 100f, BASE)
            x = q.scrivi("2", x + 0.05f * h, BASE - 0.6f * h, scala = 0.6f)
            val uguale = q.uguale(x + 0.3f * h, q.asse(BASE))
            val r = chiudi(q, uguale)
            assertEquals("$stile", "9", r?.valore)
            assertEquals("$stile", "(1+2)^2", r?.espressione)
        }
    }

    @Test
    fun parentesiRiconosciute() {
        for (stile in Stile.entries) {
            val q = Quaderno(stile)
            q.scrivi("(1)7", 100f, BASE)
            val forme = q.tratti.map { Forma(it) }
            val parentesi = forme.filter { it.parentesi }.map { q.simboli[it.id]!!.second }
            assertEquals("$stile", listOf("(", ")"), parentesi)
        }
    }

    @Test
    fun frazioniEsponentiIrregolari() {
        val sbagliati = ArrayList<String>()
        for (stile in Stile.entries) for (seme in 1..15) {
            val r = java.util.Random(seme * 7L)
            // Frazione (num)/(den) + 1, con barra inclinata e distanze variabili.
            for ((num, den, atteso) in listOf(Triple("1+2", "3", "2"), Triple("12", "4", "4"), Triple("7", "2", "4,5"))) {
                Calcolatore.azzera()
                val q = Quaderno(stile).apply { caso = java.util.Random(seme * 13L + num.hashCode()) }
                val h = q.hCifre
                val asse = q.asse(BASE)
                val x0 = 100f
                val su = (0.1f + 0.3f * r.nextFloat()) * h
                val giu = (0.1f + 0.3f * r.nextFloat()) * h
                val fineNum = q.scrivi(num, x0 + 0.15f * h, asse - su)
                val larghezza = fineNum + (0.1f + 0.2f * r.nextFloat()) * h - x0
                val pendenza = (r.nextFloat() - 0.5f) * 0.1f * larghezza
                q.tratto(x0, asse - pendenza / 2, x0 + larghezza, asse + pendenza / 2)
                q.scrivi(den, x0 + larghezza / 2f - 0.35f * h, asse + giu + h)
                val fine = q.scrivi("+1", x0 + larghezza + (0.15f + 0.3f * r.nextFloat()) * h, BASE)
                val esito = chiudi(q, q.uguale(fine + 0.3f * h, asse))
                if (esito?.valore != atteso) sbagliati += "$stile $seme ($num)/($den)+1 -> ${esito?.valore} ${esito?.espressione}"
            }
            // Potenza: base e esponente con alzata e grandezza variabili.
            for ((base, esp, atteso) in listOf(Triple("2", "10", "1024"), Triple("3", "2", "9"), Triple("10", "3", "1000"))) {
                Calcolatore.azzera()
                val q = Quaderno(stile).apply { caso = java.util.Random(seme * 17L + base.hashCode()) }
                val h = q.hCifre
                var x = q.scrivi(base, 100f, BASE)
                x = q.scrivi(esp, x + 0.05f * h, BASE - (0.45f + 0.25f * r.nextFloat()) * h, scala = 0.5f + 0.2f * r.nextFloat())
                val esito = chiudi(q, q.uguale(x + 0.3f * h, q.asse(BASE)))
                if (esito?.valore != atteso) sbagliati += "$stile $seme $base^$esp -> ${esito?.valore} ${esito?.espressione}"
            }
        }
        assertTrue(sbagliati.joinToString("\n"), sbagliati.isEmpty())
    }

    @Test
    fun frazioneLarga() {
        // Numeratore e denominatore staccati dalla barra: non toccano la fascia dell'asse.
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        val x0 = 100f
        val larghezza = q.scrivi("12", x0 + 0.2f * h, asse - 0.5f * h) + 0.2f * h - x0
        q.tratto(x0, asse + 1f, x0 + larghezza, asse - 1f)
        q.scrivi("4", x0 + 0.4f * h, asse + 0.5f * h + h)
        val uguale = q.uguale(x0 + larghezza + 0.4f * h, asse)
        val r = chiudi(q, uguale)!!
        assertEquals("3", r.valore)
        assertEquals("12/4", r.espressione)
    }

    @Test
    fun menoConScrittaSopraNonEUnaFrazione() {
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        var x = q.scrivi("8", 100f, BASE)
        q.tratto(x + 0.2f * h, asse, x + 0.9f * h, asse, testo = "-")
        // Un 5 scritto sopra il meno, poco staccato: senza niente sotto non e' un numeratore.
        q.scrivi("5", x + 0.3f * h, asse - 0.45f * h)
        x = q.scrivi("3", x + 1.1f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, asse)
        val r = chiudi(q, uguale)!!
        assertEquals("5", r.valore)
    }

    @Test
    fun rapportoCifre() {
        // Cifre alte come le maiuscole: 1,5 altezze x nello stampatello, 2,3 nel corsivo (minuscole piccole).
        assertEquals(1.5f, Calcolatore.rapportoCifre(Stile.STAMPATELLO), 0.1f)
        assertEquals(2.33f, Calcolatore.rapportoCifre(Stile.CORSIVO), 0.1f)
    }
    @Test
    fun piuConLAstaLunga() {
        // Come sul tablet: l'asta del piu' e' piu' lunga della barra e ML Kit la leggerebbe "1".
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        var x = q.scrivi("6", 100f, BASE) + 0.3f * h
        val asta = q.trattoFuori(x + 0.35f * h, asse - 0.55f * h, x + 0.32f * h, asse + 0.5f * h)
        val barra = q.trattoFuori(x, asse + 0.02f * h, x + 0.7f * h, asse - 0.03f * h)
        q.registra(listOf(asta, barra), "1")
        x = q.scrivi("5", x + h, BASE)
        val lettore = LettoreFinto(q)
        val r = chiudi(q, q.uguale(x + 0.3f * h, asse), lettore)
        assertNotNull(r)
        assertEquals("11", r!!.valore)
        // Si legge la riga intera (il contesto aiuta ML Kit con le cifre) e il piu' si rimette a posto.
        assertEquals(listOf("615"), lettore.letti)
    }

    @Test
    fun piuFuoriPostoSiLeggeAPezzi() {
        // Se ML Kit non legge un carattere per simbolo, si rilegge a pezzi attorno al piu'.
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        val fine = q.scrivi("6+5", 100f, BASE)
        val lettore = LettoreFinto(q, mapOf("6+5" to listOf("6t 15")))
        val r = chiudi(q, q.uguale(fine + 0.3f * h, asse), lettore)
        assertEquals("11", r?.valore)
        assertEquals(listOf("6+5", "6", "5"), lettore.letti)
    }

    @Test
    fun ugualeConLeBarreLontane() {
        // Le due barre scritte di corsa, distanti piu' di quanto sono lunghe.
        val q = Quaderno()
        val h = q.hCifre
        val asse = q.asse(BASE)
        val fine = q.scrivi("6+5", 100f, BASE)
        val x = fine + 0.3f * h
        val w = 0.7f * h
        val uguale = listOf(
            q.trattoFuori(x, asse - 0.6f * w, x + w, asse - 0.6f * w - 1f),
            q.trattoFuori(x + 2f, asse + 0.6f * w, x + 0.85f * w, asse + 0.6f * w),
        )
        assertEquals("11", chiudi(q, uguale)?.valore)
    }

    @Test
    fun dueMenoSuRigheDiverseNonSonoUnUguale() {
        // Due trattini a un rigo di distanza (38 unita') non fanno un uguale, anche se lunghi.
        val a = Forma(Quaderno().trattoFuori(100f, 300f, 140f, 300f))
        val b = Forma(Quaderno().trattoFuori(100f, 338f, 140f, 338f))
        assertFalse(RilevaUguale.dueTratti(a, b))
    }
    @Test
    fun suggerimentoPrimaDellUguale() = runBlocking {
        val q = Quaderno()
        val fine = q.scrivi("12+7", 100f, BASE)
        val s = Calcolatore.suggerisci(q.tratti, q.tratti.last(), LettoreFinto(q))
        assertNotNull(s)
        assertEquals("19", s!!.valore)
        assertFalse(s.conUguale)
        assertEquals(fine, s.fine, 0.5f * q.hCifre)
        // "= 19" a destra della formula, sulla stessa riga.
        val tratti = Calcolatore.scriviSuggerimento(s, q.stile) { q.nuovoId() }
        assertTrue(tratti.isNotEmpty())
        assertTrue(tratti.all { t -> t.xs().min() > fine })
        assertEquals(BASE, tratti.maxOf { t -> t.ys().max() }, 0.15f * q.hCifre)
    }

    @Test
    fun nienteSuggerimentoSeIlRisultatoCeGia() = runBlocking {
        val q = Quaderno()
        val (_, uguale) = formula(q, "3+4")
        val r = chiudi(q, uguale)!!
        q.tratti += r.tratti
        assertNull(Calcolatore.suggerisci(q.tratti, uguale.last(), LettoreFinto(q)))
    }

    @Test
    fun suggerimentoConLUgualeNonLetto() = runBlocking {
        // L'uguale e' stato scritto ma la formula non e' stata letta: si propone solo il risultato.
        val q = Quaderno()
        val (_, uguale) = formula(q, "6+5")
        q.tratti += uguale
        val s = Calcolatore.suggerisci(q.tratti, uguale.last(), LettoreFinto(q))
        assertEquals("11", s?.valore)
        assertTrue(s!!.conUguale)
    }

    @Test
    fun nienteSuggerimentoSulTesto() = runBlocking {
        val q = Quaderno()
        q.parole("ciao", 100f, BASE)
        assertNull(Calcolatore.suggerisci(q.tratti, q.tratti.last(), LettoreFinto(q)))
    }
    @Test
    fun formuleLunghe() {
        // Righe lunghe scritte di seguito: tutta la formula deve arrivare al lettore.
        val casi = listOf(
            "(12+8)x3-4:2+5" to "63",
            "3,5x(2+4)-7:(1+1)" to "17,5",
            "1+2+3+4+5+6+7+8+9+10" to "55",
            "((2+3)x(4+5)-6):(7-4)" to "13",
            "1234567+7654321" to "8888888",
        )
        for (stile in Stile.entries) for ((testo, atteso) in casi) {
            Calcolatore.azzera()
            val q = Quaderno(stile)
            val (_, uguale) = formula(q, testo, x = 40f)
            assertEquals("$stile $testo", atteso, chiudi(q, uguale)?.valore)
        }
    }

    @Test
    fun potenzaInMezzoAUnaFormulaLunga() {
        val q = Quaderno()
        val h = q.hCifre
        // 100 - 2⁵ + (4+6)x2 = 88
        var x = q.scrivi("100-2", 40f, BASE)
        x = q.scrivi("5", x + 0.05f * h, BASE - 0.55f * h, scala = 0.6f)
        x = q.scrivi("+(4+6)x2", x + 0.15f * h, BASE)
        val uguale = q.uguale(x + 0.3f * h, q.asse(BASE))
        assertEquals("88", chiudi(q, uguale)?.valore)
    }
}
