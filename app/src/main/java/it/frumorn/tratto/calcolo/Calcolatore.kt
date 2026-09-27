package it.frumorn.tratto.calcolo

import android.content.Context
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.scrittura.BellaScrittura
import it.frumorn.tratto.scrittura.Stile
import it.frumorn.tratto.scrittura.Trascrittore
import kotlinx.coroutines.CancellationException

/**
 * Il risultato di una nota matematica: l'espressione letta (per esempio "3×(4+5)", "(1+2)/3",
 * "2^10"), il valore scritto all'italiana ("27", "0,333333") e i tratti da aggiungere alla
 * pagina, a destra dell'uguale.
 */
data class Risultato(val espressione: String, val valore: String, val tratti: List<Tratto>)

/**
 * Note matematiche: quando si scrive un'espressione seguita da "=", si legge l'espressione, la
 * si calcola e si scrive il risultato dopo l'uguale con la bella scrittura.
 *
 * Tutto parte da [dopoTratto], da chiamare a ogni tratto finito. Il controllo dell'uguale e' solo
 * geometrico (due trattini orizzontali uno sopra l'altro, scritti di seguito entro 2,5 secondi,
 * oppure un tratto solo a zeta schiacciata): la scrittura normale non passa mai da ML Kit. Poi si
 * raccolgono i tratti della formula a sinistra ([Raccoglitore]), se ne trova la struttura
 * ([Struttura]) e ML Kit legge solo i pezzi lineari ([Lettura]). Se il testo letto non e' una
 * formula numerica con almeno un'operazione (per esempio "vincoli con =") non succede niente;
 * lo stesso con un calcolo impossibile (divisione per zero, radice di un negativo).
 *
 * Si capiscono: + − × (x, *, ·) : ÷ /, parentesi, virgola decimale, prodotto implicito (2(3+4),
 * 2π), frazioni con la barra (anche una dentro l'altra), esponenti in alto a destra (anche dopo
 * una parentesi chiusa), radici con la barra nello stesso tratto o in un tratto a parte, radici
 * senza barra davanti a un numero o a una parentesi, π, %.
 *
 * ML Kit non ha un modello per la matematica (gli identificatori sono lingue, "zxx-Zsym-x-shapes",
 * "zxx-Zsym-x-autodraw" ed emoji): si usa il modello italiano della trascrizione, che legge bene
 * cifre e segni e non richiede un secondo download. Se il modello non c'e' non si calcola niente.
 */
object Calcolatore {
    /** Tratti finiti di recente: id e momento in cui [dopoTratto] li ha visti. */
    private val recenti = ArrayDeque<Pair<Long, Long>>()

    /** Id dei tratti che hanno chiuso un uguale gia' considerato: mai due volte lo stesso uguale. */
    private val gestiti = ArrayDeque<Long>()

    /** Millisecondi monotoni (sostituibile nei test). */
    internal var orologio: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * Da chiamare quando [nuovi] sono appena stati aggiunti alla pagina, i cui tratti sono tutti in
     * [tratti] (nuovi compresi). Se l'ultimo tratto nuovo completa un "=" alla fine di una formula,
     * restituisce il risultato da aggiungere alla pagina; altrimenti null. Va chiamata fuori dal
     * main thread: se c'e' un uguale usa ML Kit. [idNuovo] da' gli id per i tratti del risultato.
     */
    suspend fun dopoTratto(context: Context, tratti: List<Tratto>, nuovi: List<Tratto>, stile: Stile, idNuovo: () -> Long): Risultato? {
        traccia { "dopoTratto: ${nuovi.size} nuovi su ${tratti.size}" }
        val raccolta = trova(tratti, nuovi, orologio()) ?: return null.also { traccia { "nessun uguale" } }
        val riga = Struttura.analizza(raccolta.forme, raccolta.h)
        traccia { "uguale trovato: ${raccolta.forme.size} tratti, schema ${Lettura.schema(riga).parti.joinToString("") { if (it is Lettura.Fissa) it.testo else "□" }}" }
        if (Lettura.banale(riga)) return null
        if (Struttura.ambigua(riga)) return null.also { traccia { "simboli uno sopra l'altro senza struttura: niente risultato" } }
        return try {
            if (!Trascrittore.modelloPronto(context)) return null
            BellaScrittura.prepara(context)
            calcola(raccolta, riga, stile, idNuovo) { t, pre, h ->
                Trascrittore.candidati(context, t, pre, h).also { c -> traccia { "letti (${t.size} tratti): ${c.take(5)}" } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun tracciaPubblica(messaggio: () -> String) = traccia(messaggio)

    /** Diagnostica nel logcat (tag TrattoCalcolo); non fa niente sulla JVM dei test. */
    private inline fun traccia(messaggio: () -> String) {
        runCatching { android.util.Log.d("TrattoCalcolo", messaggio()) }
    }

    /** Come [dopoTratto], con il lettore e l'ora dati: per i test sulla JVM. */
    internal suspend fun dopoTratto(
        tratti: List<Tratto>,
        nuovi: List<Tratto>,
        stile: Stile,
        idNuovo: () -> Long,
        lettore: Lettore,
        adesso: Long,
    ): Risultato? {
        val raccolta = trova(tratti, nuovi, adesso) ?: return null
        val riga = Struttura.analizza(raccolta.forme, raccolta.h)
        if (Lettura.banale(riga) || Struttura.ambigua(riga)) return null
        return calcola(raccolta, riga, stile, idNuovo, lettore)
    }

    /** Dimentica i tratti recenti e gli uguali gia' visti (per i test). */
    internal fun azzera() = synchronized(this) {
        recenti.clear()
        gestiti.clear()
    }

    /** Un uguale appena completato con la formula alla sua sinistra, o null: solo geometria. */
    internal fun trova(tratti: List<Tratto>, nuovi: List<Tratto>, adesso: Long): Raccolta? {
        val precedente: Long?
        synchronized(this) {
            for (t in nuovi) recenti.addLast(t.id to adesso)
            while (recenti.size > 32) recenti.removeFirst()
            val ultimo = nuovi.lastOrNull() ?: return null
            val i = recenti.indexOfLast { it.first == ultimo.id }
            precedente = if (i > 0 && adesso - recenti[i - 1].second <= RilevaUguale.FINESTRA_MS) recenti[i - 1].first else null
        }
        val ultimo = nuovi.last()
        if (ultimo.penna == Penna.EVIDENZIATORE || ultimo.quanti < 2) return null
        val f2 = Forma(ultimo)
        traccia { "ultimo: w=${f2.larghezza} h=${f2.altezza} orizz=${f2.orizzontale} punti=${ultimo.quanti} prec=$precedente" }
        val uguale = when {
            f2.orizzontale -> {
                val t1 = precedente?.let { id -> tratti.firstOrNull { it.id == id } ?: nuovi.firstOrNull { it.id == id } }
                    ?: return null.also { traccia { "barra senza precedente (prec=$precedente)" } }
                if (t1.penna == Penna.EVIDENZIATORE) return null
                val f1 = Forma(t1)
                if (!RilevaUguale.dueTratti(f1, f2)) return null.also {
                    traccia { "due barre scartate: w1=${f1.larghezza} w2=${f2.larghezza} h1=${f1.altezza} h2=${f2.altezza} dy=${f1.cy - f2.cy} or1=${f1.orizzontale}" }
                }
                Uguale(listOf(f1, f2))
            }
            RilevaUguale.unTratto(f2) -> Uguale(listOf(f2))
            else -> return null
        }
        val pagina = (tratti + nuovi).distinctBy { it.id }.filter { it.penna != Penna.EVIDENZIATORE && it.quanti > 0 }.map { Forma(it) }
        if (!RilevaUguale.libero(uguale, pagina)) return null.also { traccia { "uguale non libero" } }
        synchronized(this) {
            if (ultimo.id in gestiti) return null
            gestiti.addLast(ultimo.id)
            while (gestiti.size > 64) gestiti.removeFirst()
        }
        return Raccoglitore.raccogli(pagina, uguale)
    }

    /** ML Kit legge i pezzi lineari di [riga], la formula si calcola e il risultato si scrive. */
    private suspend fun calcola(raccolta: Raccolta, riga: Riga, stile: Stile, idNuovo: () -> Long, lettore: Lettore): Risultato? {
        val formula = Lettura.leggi(riga, lettore) ?: return null
        val valore = formula.nodo.valuta() ?: return null
        val testo = Numero.formatta(valore)
        return Risultato(formula.nodo.stampa(), testo, scrivi(testo, riga, raccolta, stile, idNuovo))
    }

    /**
     * Il risultato in bella scrittura subito a destra dell'uguale, con le cifre alte come quelle
     * della formula, sulla stessa linea di base delle cifre della riga principale (o centrato
     * sull'asse dell'uguale se la riga principale ha solo frazioni), con colore, penna e
     * spessore dell'uguale.
     */
    private fun scrivi(testo: String, riga: Riga, raccolta: Raccolta, stile: Stile, idNuovo: () -> Long): List<Tratto> {
        val u = raccolta.uguale
        val alti = riga.gruppiNormali().filter {
            it.speciale == null && it.altezza >= 0.5f * riga.h && it.altezza >= 0.3f * it.larghezza && it.forme.singleOrNull()?.parentesi != true
        }
        val hCifre = if (alti.isNotEmpty()) mediana(alti.map { it.altezza }) else raccolta.h
        val principali = riga.voci.map { it.elemento }.filterIsInstance<Gruppo>()
            .filter { it.speciale == null && it.altezza in 0.7f * hCifre..1.3f * hCifre }
        val base = if (principali.isNotEmpty()) mediana(principali.map { it.basso }) else u.asse + 0.5f * hCifre
        val altezzaX = (hCifre / rapportoCifre(stile)).coerceAtLeast(2f)
        val x = u.dx + 0.35f * hCifre
        val y = base - BellaScrittura.ascesa(stile, altezzaX)
        val modello = u.ultimo
        val tratti = BellaScrittura.componi(testo, stile, x, y, altezzaX, 0f, modello.colore, modello.penna, modello.spessore, idNuovo)
        for (t in tratti) for (i in 0 until t.quanti) t.punti[i * Tratto.CAMPI + 4] = ORIENTAMENTO_RISULTATO
        return tratti
    }

    /**
     * L'orientamento della penna nei tratti dei risultati: un valore impossibile (quelli veri
     * vanno da 0 a 2π, -1 vuol dire "non misurato") che resta salvato con la nota e che il
     * disegno tratta come "non misurato". Serve a riconoscere un risultato scritto prima, anche
     * dopo aver riaperto la nota: non entra nella formula seguente sulla stessa riga
     * ("3+4=7  2+2=" non diventa 72+2).
     */
    internal const val ORIENTAMENTO_RISULTATO = -2f

    /** Vero se [t] e' un risultato scritto dalle note matematiche. */
    internal fun risultato(t: Tratto): Boolean = t.quanti > 0 && t.punti[4] == ORIENTAMENTO_RISULTATO

    private val rapporti = HashMap<Stile, Float>()

    /** Altezza delle cifre del font diviso l'altezza delle minuscole (circa 1,5). */
    internal fun rapportoCifre(stile: Stile): Float = synchronized(rapporti) {
        rapporti.getOrPut(stile) {
            val altezze = ('0'..'9').mapNotNull { c ->
                val tratti = BellaScrittura.componi(c.toString(), stile, 0f, 0f, 100f, 0f, 0, Penna.PENNA, 1f) { 0L }
                if (tratti.isEmpty()) null else unione(tratti.map { Forma(it) }).altezza
            }
            if (altezze.isEmpty()) 1.5f else (mediana(altezze) / 100f).coerceIn(0.8f, 3f)
        }
    }
}
