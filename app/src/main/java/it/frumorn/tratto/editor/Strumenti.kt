package it.frumorn.tratto.editor

import it.frumorn.tratto.data.Penna

enum class Strumento { PENNA, GOMMA, LAZO }

enum class ModoGomma { TRATTO, PARZIALE }

/** Impostazioni di una penna: colore e spessore in unita' di pagina. */
data class ImpostazioniPenna(val colore: Int, val spessore: Float)

/** Stato degli strumenti dell'editor. Immutabile: ogni cambio crea una copia. */
data class StatoStrumenti(
    val strumento: Strumento = Strumento.PENNA,
    val penna: Penna = Penna.PENNA,
    val penne: Map<Penna, ImpostazioniPenna> = PREDEFINITE,
    val modoGomma: ModoGomma = ModoGomma.TRATTO,
    /** Raggio della gomma in dp sullo schermo. */
    val raggioGomma: Float = 14f,
    val coloriRecenti: List<Int> = emptyList(),
) {
    val corrente: ImpostazioniPenna get() = penne.getValue(penna)

    companion object {
        val PREDEFINITE = mapOf(
            Penna.STILOGRAFICA to ImpostazioniPenna(0xFF1E1E1E.toInt(), 3.6f),
            Penna.PENNA to ImpostazioniPenna(0xFF1E1E1E.toInt(), 3.2f),
            Penna.MATITA to ImpostazioniPenna(0xFF3A3A3A.toInt(), 3f),
            Penna.EVIDENZIATORE to ImpostazioniPenna(0xFFFFD600.toInt(), 22f),
            Penna.PENNELLO to ImpostazioniPenna(0xFF6546F3.toInt(), 6.5f),
        )

        /** Tavolozza principale: neri e grigi, poi i colori piu' usati per prendere appunti. */
        val TAVOLOZZA = listOf(
            0xFF1E1E1E, 0xFF5B5B66, 0xFFFFFFFF, 0xFF6546F3, 0xFF2F6BFF, 0xFF0FA3B1,
            0xFF1E9E5A, 0xFFF2B705, 0xFFFF7A1A, 0xFFE0354B, 0xFFC0399E, 0xFF8A5A3C,
        ).map { it.toInt() }

        /** Nomi dei colori della tavolozza, per TalkBack. */
        val NOMI_COLORI = listOf("Nero", "Grigio", "Bianco", "Viola", "Blu", "Turchese", "Verde", "Giallo", "Arancione", "Rosso", "Magenta", "Marrone")

        fun nomeColore(c: Int): String = TAVOLOZZA.indexOf(c).let { if (it >= 0) NOMI_COLORI[it] else "Colore personalizzato" }

        val SPESSORI_EVIDENZIATORE = 12f..48f
        val SPESSORI = 1f..24f
    }
}
