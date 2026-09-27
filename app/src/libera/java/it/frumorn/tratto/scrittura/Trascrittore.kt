package it.frumorn.tratto.scrittura

import android.content.Context
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trascrittore della versione libera: stessa interfaccia di quello della versione completa, ma il
 * riconoscimento della scrittura non c'e' (ML Kit non e' software libero e F-Droid non lo accetta).
 * Il modello non e' mai pronto, non si scarica niente e la UI nasconde le voci che ne hanno
 * bisogno ([DISPONIBILE] falso): trascrizione, bella scrittura e calcoli automatici.
 */
object Trascrittore {
    const val LINGUA = "it"

    /** Il riconoscimento della scrittura non c'e' in questa versione dell'app. */
    const val DISPONIBILE = false

    private const val NON_DISPONIBILE = "Non disponibile nella versione libera"

    /** Stesso stato della versione completa; qui resta [StatoModello.Assente] o [StatoModello.Errore]. */
    sealed interface StatoModello {
        data object Assente : StatoModello
        data object InDownload : StatoModello
        data object Pronto : StatoModello
        data class Errore(val messaggio: String) : StatoModello
    }

    private val _stato = MutableStateFlow<StatoModello>(StatoModello.Assente)
    val stato: StateFlow<StatoModello> = _stato.asStateFlow()

    /** Sempre falso: non c'e' nessun modello. */
    suspend fun modelloPronto(context: Context): Boolean = false

    /** Non scarica niente: mette in [stato] l'errore da mostrare e restituisce falso. */
    suspend fun scaricaModello(context: Context, soloWifi: Boolean = false): Boolean {
        _stato.value = StatoModello.Errore(NON_DISPONIBILE)
        return false
    }

    /**
     * Come nella versione completa senza modello: testo vuoto se non c'e' niente da leggere,
     * altrimenti IllegalStateException.
     */
    suspend fun trascrivi(context: Context, tratti: List<Tratto>): String {
        if (tratti.none { it.penna != Penna.EVIDENZIATORE && it.quanti > 0 }) return ""
        throw IllegalStateException(NON_DISPONIBILE)
    }

    /** Nessun candidato: le note matematiche non calcolano niente. */
    suspend fun candidati(context: Context, tratti: List<Tratto>, preContesto: String = "", altezzaRiga: Float = 0f): List<String> =
        emptyList()

    suspend fun chiudi() = Unit
}
