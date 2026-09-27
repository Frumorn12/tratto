package it.frumorn.tratto.scrittura

import java.io.DataInputStream
import java.io.IOException

/**
 * Un font Hershey a tratto singolo, letto dall'asset generato da tools/font_hershey.py.
 *
 * Le coordinate sono quelle originali di Hershey (interi piccoli, y verso il basso):
 * [maiuscole] e' la cima delle maiuscole, [lineaX] la cima delle minuscole, [base] la linea
 * su cui poggiano le lettere e [fondo] il punto piu' basso dei discendenti (g, p, q...).
 */
internal class FontHershey(
    val stile: Stile,
    val maiuscole: Int,
    val lineaX: Int,
    val base: Int,
    val fondo: Int,
    private val primo: Int,
    private val glifi: Array<Glifo>,
) {
    /** Un glifo: margini orizzontali e tratti come coppie piatte [x0, y0, x1, y1, ...]. */
    class Glifo(val sinistra: Int, val destra: Int, val tratti: List<FloatArray>)

    /** Altezza delle minuscole in unita' del font. */
    val altezzaX: Int get() = base - lineaX

    /** Il punto piu' alto di lettere e cifre: nello stampatello il puntino della i supera le maiuscole. */
    val cima: Int = (('A'..'Z') + ('a'..'z') + ('0'..'9')).mapNotNull { glifo(it) }
        .flatMap { g -> g.tratti.flatMap { t -> (1 until t.size step 2).map { t[it].toInt() } } }
        .fold(maiuscole) { a, b -> minOf(a, b) }

    fun glifo(c: Char): Glifo? = glifi.getOrNull(c.code - primo)

    companion object {
        private const val FIRMA = "THF1"

        /** Legge tutti i font del file; l'ordine degli stili nel file non conta. */
        fun leggi(dati: ByteArray): Map<Stile, FontHershey> {
            val din = DataInputStream(dati.inputStream())
            val firma = ByteArray(4).also { din.readFully(it) }.decodeToString()
            if (firma != FIRMA) throw IOException("Font della bella scrittura non valido ($firma)")
            val quanti = din.readUnsignedByte()
            val out = HashMap<Stile, FontHershey>()
            repeat(quanti) {
                val stile = Stile.entries[din.readUnsignedByte()]
                val maiuscole = din.readByte().toInt()
                val lineaX = din.readByte().toInt()
                val base = din.readByte().toInt()
                val fondo = din.readByte().toInt()
                val primo = din.readUnsignedByte()
                val numero = din.readUnsignedByte()
                val glifi = Array(numero) {
                    val sinistra = din.readByte().toInt()
                    val destra = din.readByte().toInt()
                    val tratti = List(din.readUnsignedByte()) {
                        FloatArray(din.readUnsignedByte() * 2) { din.readByte().toFloat() }
                    }
                    Glifo(sinistra, destra, tratti)
                }
                out[stile] = FontHershey(stile, maiuscole, lineaX, base, fondo, primo, glifi)
            }
            return out
        }
    }
}
