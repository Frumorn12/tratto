package it.frumorn.tratto.data

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formato binario dei tratti di una pagina (file .tp).
 *
 *   "TRTP" | versione:u8 | quanti:i32 | tratti...
 *   tratto: id:i64 | penna:u8 | colore:i32 | spessore:f32 | punti:i32 | punti*6 f32
 *
 * Little endian, letto in un colpo solo con un ByteBuffer: una pagina piena si carica in pochi ms.
 */
object FormatoPagina {
    private val MAGIA = byteArrayOf('T'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte())
    private const val VERSIONE: Byte = 1

    fun leggi(file: File): MutableList<Tratto> {
        if (!file.exists()) return mutableListOf()
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val magia = ByteArray(4).also { b.get(it) }
        if (!magia.contentEquals(MAGIA)) throw IOException("File di pagina non valido: ${file.name}")
        val versione = b.get()
        if (versione > VERSIONE) throw IOException("Versione di pagina $versione non supportata")
        val quanti = b.int
        val tratti = ArrayList<Tratto>(quanti)
        val penne = Penna.entries
        repeat(quanti) {
            val id = b.long
            val penna = penne.getOrElse(b.get().toInt()) { Penna.PENNA }
            val colore = b.int
            val spessore = b.float
            val n = b.int
            val punti = FloatArray(n * Tratto.CAMPI)
            b.asFloatBuffer().get(punti)
            b.position(b.position() + punti.size * 4)
            tratti += Tratto(id, penna, colore, spessore, punti)
        }
        return tratti
    }

    /** Scrittura atomica: file temporaneo e poi rename, cosi' un crash non lascia pagine a meta'. */
    fun scrivi(file: File, tratti: List<Tratto>) {
        val dimensione = 9 + tratti.sumOf { 25 + it.punti.size * 4 }
        val b = ByteBuffer.allocate(dimensione).order(ByteOrder.LITTLE_ENDIAN)
        b.put(MAGIA).put(VERSIONE).putInt(tratti.size)
        for (t in tratti) {
            b.putLong(t.id).put(t.penna.ordinal.toByte()).putInt(t.colore).putFloat(t.spessore).putInt(t.quanti)
            b.asFloatBuffer().put(t.punti)
            b.position(b.position() + t.punti.size * 4)
        }
        scriviAtomico(file, b.array())
    }
}

fun scriviAtomico(file: File, dati: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { out ->
        out.write(dati)
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) throw IOException("Impossibile salvare ${file.name}")
}

