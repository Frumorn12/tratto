package it.frumorn.tratto.editor

/**
 * Quanto aspettare prima di salvare o mandare una modifica: [pausa] dopo l'ultima, ma mai oltre
 * [massimo] dalla prima non ancora salvata. Chi scrive senza fermarsi (una riga a mano, una frase
 * alla tastiera) non fa ripartire l'attesa all'infinito: ogni [massimo] quello che c'e' parte.
 */
object Delay {
    fun ritardo(primaModifica: Long, ora: Long, pausa: Long, massimo: Long): Long =
        minOf(pausa, maxOf(0L, primaModifica + massimo - ora))
}
