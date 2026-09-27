package it.frumorn.tratto.calcolo

import kotlin.math.abs

/** I tratti di una formula scritta a sinistra di un uguale, con l'altezza tipica dei suoi simboli. */
internal class Raccolta(val forme: List<Forma>, val uguale: Uguale, val h: Float)

/**
 * Raccoglie dalla pagina i tratti della formula che finisce con [Uguale], senza ML Kit.
 *
 * La formula sta su una "riga di matematica" il cui asse passa per il centro dell'uguale: e'
 * l'altezza del meno, del piu' e delle barre di frazione, e taglia a meta' le cifre. Si parte
 * dall'uguale e si va verso sinistra prendendo i tratti che attraversano l'asse, finche' non si
 * trova un vuoto largo (fine della formula) o un altro uguale (formula precedente sulla stessa
 * riga). Poi si aggiungono i tratti fuori dall'asse che appartengono alla formula: numeratori e
 * denominatori sopra e sotto una barra di frazione, esponenti in alto a destra di un simbolo,
 * puntini e virgole vicino all'asse.
 */
internal object Raccoglitore {
    /** Vuoto massimo tra due simboli della stessa formula, in altezze dei simboli. */
    private const val VUOTO = 2.0f

    /** Mezza altezza della fascia intorno all'asse, in altezze dei simboli. */
    private const val FASCIA = 0.35f

    fun raccogli(pagina: List<Forma>, u: Uguale): Raccolta? {
        val idUguale = u.forme.map { it.id }.toHashSet()
        val tutti = pagina.filter { it.id !in idUguale && it.quanti > 0 }
        // I risultati scritti prima non fanno parte di nessuna formula.
        val altri = tutti.filter { !Calcolatore.risultato(it.tratto) }
        val asse = u.asse
        val h = stimaAltezza(altri, u)

        // Qualcosa subito a destra dell'uguale: un risultato gia' scritto, oppure l'uguale sta
        // in mezzo a una riga. In tutti e due i casi non si scrive niente.
        val occupato = tutti.any {
            it.cx > u.dx - 0.1f * u.larghezza && it.sx < u.dx + 2.5f * h &&
                it.basso > asse - 0.7f * h && it.alto < asse + 0.7f * h
        }
        if (occupato) return null.also { Calcolatore.tracciaPubblica { "raccolta: occupato a destra" } }

        val scelti = LinkedHashSet(cammina(tutti, u, h))
        Calcolatore.tracciaPubblica { "raccolta: asse=$asse h=$h sull'asse=${scelti.size} " + tutti.joinToString { "[${it.sx.toInt()}-${it.dx.toInt()} ${it.alto.toInt()}-${it.basso.toInt()}]" } }
        if (scelti.isEmpty()) return null
        val apici = HashSet<Forma>()
        var cambiato = true
        while (cambiato) {
            cambiato = false
            for (c in frazioni(scelti, altri, h)) if (scelti.add(c)) cambiato = true
            val sinistra = scelti.minOf { it.sx }
            for (c in altri) {
                if (c in scelti || c.cx < sinistra - 0.3f * h || c.cx > u.sx || c.altezza > 3f * h) continue
                val apice = apice(c, scelti, apici, asse, h)
                if (apice || segnetto(c, asse, h) || barraRadice(c, scelti, h)) {
                    scelti += c
                    if (apice) apici += c
                    cambiato = true
                }
            }
        }
        if (scelti.size < 2) return null.also { Calcolatore.tracciaPubblica { "raccolta: solo ${scelti.size} simbolo, apici=${apici.size}" } }
        return Raccolta(scelti.sortedBy { it.id }, u, h)
    }

    /**
     * Altezza tipica dei simboli vicino all'uguale ([altezzaCifre]) dai tratti non piatti che
     * attraversano l'asse subito a sinistra. Senza tratti, circa la larghezza dell'uguale.
     */
    internal fun stimaAltezza(altri: List<Forma>, u: Uguale): Float {
        val h0 = (1.1f * u.larghezza).coerceIn(8f, 150f)
        val vicini = altri
            .filter { it.dx <= u.sx + 0.5f * u.larghezza && it.dx >= u.sx - 4f * h0 && attraversa(it, u.asse, h0) }
            .filter { it.altezza >= 0.3f * it.larghezza && it.altezza >= 0.3f * h0 && it.altezza <= 3f * h0 && !it.parentesi }
            .sortedByDescending { it.dx }
            .take(6)
        if (vicini.isEmpty()) return h0
        return altezzaCifre(vicini.map { it.altezza }).coerceIn(0.5f * h0, 2.5f * h0)
    }

    private fun attraversa(f: Forma, asse: Float, h: Float) = f.alto <= asse + FASCIA * h && f.basso >= asse - FASCIA * h

    /**
     * I tratti sull'asse, da destra verso sinistra fino al primo vuoto largo, al primo risultato
     * scritto da noi o al primo uguale.
     */
    private fun cammina(tutti: List<Forma>, u: Uguale, h: Float): List<Forma> {
        // Oltre ai tratti sull'asse si guardano anche quelli appena sopra o sotto (esponenti,
        // numeratori, denominatori): non entrano qui nella formula (ci pensano apici e frazioni),
        // ma fanno da ponte, cosi' un esponente tra la base e l'uguale non sembra un vuoto.
        val vicini = tutti
            .filter {
                it.cx < u.sx && it.dx <= u.sx + 0.5f * u.larghezza && it.altezza <= 4f * h &&
                    it.basso >= u.asse - 2.2f * h && it.alto <= u.asse + 2.2f * h
            }
            .sortedByDescending { it.dx }
        val scelti = ArrayList<Forma>()
        var fronte = u.sx
        for (f in vicini) {
            if (f.dx < fronte - VUOTO * h || Calcolatore.risultato(f.tratto)) break
            if (attraversa(f, u.asse, h)) scelti += f
            if (f.sx < fronte) fronte = f.sx
        }
        // Un uguale gia' scritto sulla stessa riga chiude la formula: si tiene solo quello che
        // c'e' dopo (per esempio "3+4=7  2+2=" -> "2+2").
        val piatti = scelti.filter { it.orizzontale }
        var taglio = Float.NEGATIVE_INFINITY
        for (i in piatti.indices) for (j in i + 1 until piatti.size) {
            val a = piatti[i]
            val b = piatti[j]
            if (RilevaUguale.dueTratti(a, b)) {
                val v = Uguale(listOf(a, b))
                if (RilevaUguale.libero(v, scelti) && v.dx > taglio) taglio = v.dx
            }
        }
        return if (taglio.isFinite()) scelti.filter { it.cx > taglio } else scelti
    }

    /**
     * Numeratori e denominatori: per ogni trattino orizzontale preso, i tratti nella sua colonna
     * sopra e sotto, legati alla barra da una catena di tratti vicini. Si prendono solo se la
     * barra ha simboli veri da tutte e due le parti (quelli gia' presi contano): cosi' una
     * scritta della riga sopra un meno non entra nella formula.
     */
    private fun frazioni(scelti: Set<Forma>, altri: List<Forma>, h: Float): List<Forma> {
        val out = ArrayList<Forma>()
        for (b in scelti) {
            if (!b.orizzontale || b.larghezza < 0.45f * h) continue
            val margine = 0.15f * b.larghezza + 0.1f * h
            val sx = b.sx - margine
            val dx = b.dx + margine
            fun inColonna(f: Forma) = f !== b && f.cx in sx..dx && f.altezza <= 3f * h
            val presiSopra = scelti.filter { inColonna(it) && it.cy < b.cy }
            val presiSotto = scelti.filter { inColonna(it) && it.cy > b.cy }
            val liberi = altri.filter { it !in scelti && inColonna(it) }
            val sopra = catena(b, presiSopra, liberi.filter { it.cy < b.cy && it.basso <= b.basso + 0.15f * h }, h)
            val sotto = catena(b, presiSotto, liberi.filter { it.cy > b.cy && it.alto >= b.alto - 0.15f * h }, h)
            if ((presiSopra + sopra).any { !it.piccolo(h) } && (presiSotto + sotto).any { !it.piccolo(h) }) {
                out += sopra
                out += sotto
            }
        }
        return out
    }

    /** I [candidati] raggiungibili dalla [barra] (o dai [presi]) passando per tratti vicini in verticale. */
    private fun catena(barra: Forma, presi: List<Forma>, candidati: List<Forma>, h: Float): List<Forma> {
        val legati = ArrayList<Forma>(presi)
        legati += barra
        val out = ArrayList<Forma>()
        val resto = candidati.toMutableList()
        var cambiato = true
        while (cambiato) {
            cambiato = false
            val iter = resto.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (legati.any { distanzaY(it, c) <= 0.7f * h && sovrapposizioneX(it, c) > -0.3f * h }) {
                    legati += c
                    out += c
                    iter.remove()
                    cambiato = true
                }
            }
        }
        return out
    }

    /**
     * Un esponente: piccolo, sopra l'asse, in alto a destra di un simbolo della formula (o
     * accanto a un altro esponente, come lo 0 di 10). Non troppo in alto, se no e' la riga sopra.
     */
    private fun apice(c: Forma, scelti: Set<Forma>, apici: Set<Forma>, asse: Float, h: Float): Boolean {
        if (c.altezza > 0.85f * h || c.larghezza > 1.5f * h) return false
        if (c.cy > asse - 0.2f * h || c.basso < asse - 0.9f * h) return false
        return scelti.any { s ->
            if (s in apici) {
                abs(c.cy - s.cy) <= 0.35f * h && c.sx >= s.cx && c.sx <= s.dx + 0.6f * h
            } else {
                s.altezza >= 0.5f * h && c.sx >= s.cx && c.sx <= s.dx + 0.8f * h && c.cy < s.alto + 0.35f * s.altezza
            }
        }
    }

    /** La barra di una radice scritta a parte: un trattino che parte dalla punta di un segno di radice. */
    private fun barraRadice(c: Forma, scelti: Set<Forma>, h: Float): Boolean {
        if (!c.orizzontale) return false
        return scelti.any { s ->
            val r = Struttura.segnoRadice(s, h)
            r != null && r.fineBarra == null && abs(c.sx - r.xPunta) <= 0.4f * h && abs(c.cy - r.yCima) <= 0.4f * h
        }
    }

    /** Puntini e virgole vicino all'asse: la virgola dei decimali, il punto per, i puntini del diviso. */
    private fun segnetto(c: Forma, asse: Float, h: Float): Boolean =
        maxOf(c.larghezza, c.altezza) <= 0.45f * h && abs(c.cy - asse) <= 0.9f * h
}
