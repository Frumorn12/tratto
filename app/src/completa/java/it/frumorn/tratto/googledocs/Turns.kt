package it.frumorn.tratto.googledocs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Gli aggiornamenti dei documenti Google: uno alla volta per tutte le note ([mutex]), e per ogni nota
 * al massimo uno in attesa. Un aggiornamento nuovo prende il posto di quello che aspetta ancora (il
 * suo ritardo o il suo turno); quello gia' partito non si interrompe e arriva in fondo.
 */
internal class Turns(private val scope: CoroutineScope, val mutex: Mutex = Mutex()) {
    private val attese = HashMap<String, Job>()

    fun programma(chiave: String, ritardo: Long, lavoro: suspend () -> Unit) {
        synchronized(attese) {
            attese.remove(chiave)?.cancel()
            attese[chiave] = scope.launch {
                delay(ritardo)
                // Il turno si aspetta ancora annullabili: se nel frattempo arriva un'altra modifica,
                // questo aggiornamento lascia il posto al suo.
                mutex.lock()
                try {
                    withContext(NonCancellable) { lavoro() }
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    /** Toglie l'aggiornamento in attesa della nota, se c'e' (quello in corso finisce lo stesso). */
    fun annulla(chiave: String) {
        synchronized(attese) { attese.remove(chiave)?.cancel() }
    }

    /** Esegue [lavoro] al suo turno, fuori dalla coda (per il worker in background). */
    suspend fun <T> esegui(lavoro: suspend () -> T): T = mutex.withLock { lavoro() }
}
