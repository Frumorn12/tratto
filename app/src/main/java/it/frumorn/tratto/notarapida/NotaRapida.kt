package it.frumorn.tratto.notarapida

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Regole della nota rapida che non dipendono da Android, per poterle provare nei test. */
object NotaRapida {
    private val formatoData = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ITALIAN)

    /** "Nota rapida 27 set, 18:40": data e ora scritte come nella libreria. */
    fun titolo(ms: Long, zona: ZoneId = ZoneId.systemDefault()): String =
        "Nota rapida " + formatoData.format(Instant.ofEpochMilli(ms).atZone(zona))

    /**
     * Dimensioni del foglietto in dp: circa 80% x 55% della finestra, in verticale e in orizzontale.
     * Su finestre piccole (schermo diviso) non scende sotto una misura in cui si riesce a scrivere,
     * senza pero' uscire dalla finestra.
     */
    fun dimensioni(larghezza: Float, altezza: Float): Pair<Float, Float> {
        val w = max(larghezza * 0.8f, min(larghezza - MARGINE_DP, MIN_LARGHEZZA_DP))
        val h = max(altezza * 0.55f, min(altezza - MARGINE_DP, MIN_ALTEZZA_DP))
        return w to h
    }

    /**
     * Un tocco fuori dal foglietto lo chiude, ma solo se e' davvero un tocco: breve, fermo, con un
     * solo dito. Col dito serve anche che la penna non sia stata vicina allo schermo da poco: mentre
     * si scrive il palmo si appoggia fuori dal foglietto e non deve chiuderlo. Con la penna invece
     * il tocco e' voluto, come sul foglietto della S Pen.
     */
    fun toccoFuoriChiude(conPenna: Boolean, durataMs: Long, spostato: Boolean, piuDita: Boolean, msDallaPenna: Long): Boolean {
        if (spostato || piuDita || durataMs > DURATA_TOCCO_MS) return false
        return conPenna || msDallaPenna > PENNA_RECENTE_MS
    }

    const val DURATA_TOCCO_MS = 350L
    const val PENNA_RECENTE_MS = 1200L
    private const val MARGINE_DP = 32f
    private const val MIN_LARGHEZZA_DP = 480f
    private const val MIN_ALTEZZA_DP = 360f
}
