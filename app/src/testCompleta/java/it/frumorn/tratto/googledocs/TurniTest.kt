package it.frumorn.tratto.googledocs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

/**
 * "Il polling di Google Doc quando scrivo su Tratto è lento" (Andrea, 04/11). Scrivendo senza
 * fermarsi la nota si salva ogni due secondi, mentre un aggiornamento del documento ne dura qualcuno:
 * le richieste arrivate nel frattempo non devono mettersi in fila tutte (ognuna rileggerebbe il
 * documento per niente). Ne aspetta al massimo una per nota, l'ultima; quella partita arriva in fondo.
 */
class TurniTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun duranteUnLavoroNeAspettaUnoSolo() = runBlocking {
        val turni = Turni(scope)
        val fatti = Collections.synchronizedList(ArrayList<String>())
        val partito = CompletableDeferred<Unit>()
        val via = CompletableDeferred<Unit>()
        val finito = CompletableDeferred<Unit>()
        turni.programma("nota", 0) {
            partito.complete(Unit)
            via.await()
            fatti += "primo"
        }
        withTimeout(2000) { partito.await() }
        // Tre salvataggi mentre il primo aggiornamento e' in corso: resta solo l'ultimo.
        turni.programma("nota", 0) { fatti += "secondo" }
        turni.programma("nota", 0) { fatti += "terzo" }
        turni.programma("nota", 0) { fatti += "quarto"; finito.complete(Unit) }
        delay(50)
        via.complete(Unit)
        withTimeout(2000) { finito.await() }
        delay(50)
        assertEquals(listOf("primo", "quarto"), fatti.toList())
    }

    @Test
    fun unLavoroPartitoArrivaInFondo() = runBlocking {
        val turni = Turni(scope)
        val fatti = Collections.synchronizedList(ArrayList<String>())
        val partito = CompletableDeferred<Unit>()
        val finito = CompletableDeferred<Unit>()
        turni.programma("nota", 0) {
            partito.complete(Unit)
            delay(100)
            fatti += "primo"
        }
        withTimeout(2000) { partito.await() }
        turni.annulla("nota")
        turni.programma("nota", 0) { fatti += "secondo"; finito.complete(Unit) }
        withTimeout(2000) { finito.await() }
        assertEquals(listOf("primo", "secondo"), fatti.toList())
    }

    @Test
    fun unoAllaVoltaAncheTraNoteDiverse() = runBlocking {
        val turni = Turni(scope)
        val dentro = java.util.concurrent.atomic.AtomicInteger()
        var massimo = 0
        val finiti = CompletableDeferred<Unit>()
        val quanti = java.util.concurrent.atomic.AtomicInteger()
        for (k in 1..3) {
            turni.programma("nota$k", 0) {
                val n = dentro.incrementAndGet()
                synchronized(this@TurniTest) { massimo = maxOf(massimo, n) }
                delay(30)
                dentro.decrementAndGet()
                if (quanti.incrementAndGet() == 3) finiti.complete(Unit)
            }
        }
        withTimeout(2000) { finiti.await() }
        assertEquals(1, massimo)
    }

    @Test
    fun ilRitardoSiAnnulla() = runBlocking {
        val turni = Turni(scope)
        val fatti = Collections.synchronizedList(ArrayList<String>())
        turni.programma("nota", 200) { fatti += "annullato" }
        turni.annulla("nota")
        delay(300)
        assertEquals(emptyList<String>(), fatti.toList())
    }
}
