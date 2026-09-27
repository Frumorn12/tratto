package it.frumorn.tratto.data

/** Sfondo di una pagina. */
enum class Sfondo { BIANCO, RIGHE, QUADRETTI, PUNTINI }

/** Strumenti di scrittura. L'ordine dei valori e' parte del formato su disco: aggiungere solo in fondo. */
enum class Penna { STILOGRAFICA, PENNA, MATITA, EVIDENZIATORE, PENNELLO }

/** Dimensioni logiche di una pagina A4 verticale: tutte le coordinate dei tratti sono in queste unita'. */
object Foglio {
    const val LARGHEZZA = 1000f
    const val ALTEZZA = 1414f
}

data class Cartella(
    val id: String,
    val nome: String,
    val colore: Int,
)

/** Voce dell'indice: tutto quello che serve per mostrare la libreria senza aprire le note. */
data class NotaInfo(
    val id: String,
    val titolo: String,
    val cartellaId: String?,
    val creata: Long,
    val modificata: Long,
    val preferita: Boolean,
    val pagine: Int,
    val sfondo: Sfondo,
    val conPdf: Boolean,
)

data class Pagina(
    val id: String,
    val sfondo: Sfondo,
    /** Pagina del PDF allegato da mostrare sotto i tratti, oppure -1. */
    val pdfPagina: Int = -1,
    val larghezza: Float = Foglio.LARGHEZZA,
    val altezza: Float = Foglio.ALTEZZA,
)

/**
 * Un tratto di penna. I punti sono in un unico array piatto per non creare migliaia di oggetti:
 * per ogni punto [x, y, pressione 0..1, inclinazione rad, orientamento rad, tempo ms].
 */
class Tratto(
    val id: Long,
    val penna: Penna,
    val colore: Int,
    val spessore: Float,
    val punti: FloatArray,
) {
    val quanti: Int get() = punti.size / CAMPI

    companion object {
        const val CAMPI = 6
    }
}
