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
    private const val VUOTO = 2.5f

    /** Dopo un uguale gia' scritto sulla stessa riga basta un vuoto piu' piccolo per separare le formule. */
    private const val VUOTO_DOPO_UGUALE = 1.2f

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

        // Le frazioni si cercano prima e a parte: la barra sta vicino all'asse, ma numeratore e
        // denominatore possono stare fuori dalla fascia (o sfiorarla appena) e la barra stessa,
        // in una frazione scritta un po' alta o bassa, puo' mancare la fascia.
        val blocchi = blocchiFrazione(altri, u, h)
        val scelti = LinkedHashSet(cammina(tutti, u, h, blocchi))
        Calcolatore.tracciaPubblica { "raccolta: asse=$asse h=$h sull'asse=${scelti.size} frazioni=${blocchi.size} " + tutti.joinToString { "[${it.sx.toInt()}-${it.dx.toInt()} ${it.alto.toInt()}-${it.basso.toInt()}]" } }
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
        vicini(altri, u, h0, 4f, 0.3f * h0, 3f * h0)?.let { return altezzaCifre(it).coerceIn(0.5f * h0, 2.5f * h0) }
        // Un uguale scritto piccolo non dice quanto sono alte le cifre (sul tablet: barre di 8
        // unita' dopo cifre alte 35, staccate di 58): le si cerca in una fascia da cifra normale.
        val fascia = maxOf(h0, 30f)
        vicini(altri, u, fascia, 6f, 6f, 3f * fascia)?.let { return altezzaCifre(it).coerceIn(0.5f * h0, maxOf(2.5f * h0, 90f)) }
        return h0
    }

    /** Altezze dei simboli non piatti che attraversano l'asse a sinistra dell'uguale, entro [finestra] volte [h]. */
    private fun vicini(altri: List<Forma>, u: Uguale, h: Float, finestra: Float, minima: Float, massima: Float): List<Float>? =
        altri
            .filter { it.dx <= u.sx + 0.5f * u.larghezza && it.dx >= u.sx - finestra * h && attraversa(it, u.asse, h) }
            .filter { it.altezza >= 0.3f * it.larghezza && it.altezza >= minima && it.altezza <= massima && !it.parentesi }
            .sortedByDescending { it.dx }
            .take(6)
            .map { it.altezza }
            .ifEmpty { null }

    private fun attraversa(f: Forma, asse: Float, h: Float) = f.alto <= asse + FASCIA * h && f.basso >= asse - FASCIA * h

    /**
     * I tratti sull'asse, da destra verso sinistra fino al primo vuoto largo, al primo risultato
     * scritto da noi o al primo uguale.
     */
    private fun cammina(tutti: List<Forma>, u: Uguale, h: Float, blocchi: List<Set<Forma>>): List<Forma> {
        // Oltre ai tratti sull'asse si guardano anche quelli appena sopra o sotto (esponenti,
        // numeratori, denominatori): non entrano qui nella formula (ci pensano apici e frazioni),
        // ma fanno da ponte, cosi' un esponente tra la base e l'uguale non sembra un vuoto.
        // Una frazione trovata prima conta come un pezzo solo, tutto nella formula.
        class Pezzo(val forme: Collection<Forma>, val sullAsse: Boolean) {
            val sx = forme.minOf { it.sx }
            val dx = forme.maxOf { it.dx }
        }
        val inBlocco = blocchi.flatten().toHashSet()
        val pezzi = tutti
            .filter {
                it !in inBlocco && it.cx < u.sx && it.dx <= u.sx + 0.5f * u.larghezza && it.altezza <= 4f * h &&
                    it.basso >= u.asse - 2.2f * h && it.alto <= u.asse + 2.2f * h
            }
            .map { Pezzo(listOf(it), attraversa(it, u.asse, h)) } + blocchi.map { Pezzo(it, true) }
        val scelti = ArrayList<Forma>()
        var fronte = u.sx
        for (p in pezzi.sortedByDescending { it.dx }) {
            if (p.dx < fronte - VUOTO * h || p.forme.any { Calcolatore.risultato(it.tratto) }) break
            if (p.sullAsse) scelti += p.forme
            if (p.sx < fronte) fronte = p.sx
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
        if (!taglio.isFinite()) return scelti
        // Subito dopo l'uguale di prima c'e' il suo risultato, scritto a mano: la formula nuova
        // comincia dopo il primo vuoto largo (in "3+4=7  2+2=" il 7 non fa parte di 2+2).
        val dopo = scelti.filter { it.cx > taglio }.sortedByDescending { it.dx }
        var sinistra = u.sx
        val formula = ArrayList<Forma>()
        for (f in dopo) {
            if (f.dx < sinistra - VUOTO_DOPO_UGUALE * h) break
            formula += f
            if (f.sx < sinistra) sinistra = f.sx
        }
        return formula
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
            val presiSopra = scelti.filter { Colonna.dentro(b, it, h) && it.cy < b.cy }
            val presiSotto = scelti.filter { Colonna.dentro(b, it, h) && it.cy > b.cy }
            val liberi = altri.filter { it !in scelti && Colonna.dentro(b, it, h) }
            val sopra = catena(b, presiSopra, liberi.filter { Colonna.sopra(b, it, h) }, h)
            val sotto = catena(b, presiSotto, liberi.filter { Colonna.sotto(b, it, h) }, h)
            if ((presiSopra + sopra).any { !it.piccolo(h) } && (presiSotto + sotto).any { !it.piccolo(h) }) {
                out += sopra
                out += sotto
            }
        }
        return out
    }

    /**
     * Le frazioni vicino all'asse, dalla barra piu' lunga (cosi' in una frazione di frazioni la
     * barra principale prende tutto): un trattino orizzontale non lontano dall'asse con simboli
     * veri sia sopra sia sotto, nella sua colonna. Ogni blocco e' barra + numeratore + denominatore.
     */
    private fun blocchiFrazione(altri: List<Forma>, u: Uguale, h: Float): List<Set<Forma>> {
        val barre = altri
            .filter {
                it.orizzontale && it.larghezza >= 0.45f * h && it.cx < u.sx && it.dx <= u.sx + 0.5f * u.larghezza &&
                    abs(it.cy - u.asse) <= 1.2f * h
            }
            .sortedByDescending { it.larghezza }
        val presi = HashSet<Forma>()
        val out = ArrayList<Set<Forma>>()
        for (b in barre) {
            if (b in presi) continue
            val liberi = altri.filter { it !in presi && Colonna.dentro(b, it, h) }
            val sopra = catena(b, emptyList(), liberi.filter { Colonna.sopra(b, it, h) }, h)
            val sotto = catena(b, emptyList(), liberi.filter { Colonna.sotto(b, it, h) }, h)
            if (sopra.any { !it.piccolo(h) } && sotto.any { !it.piccolo(h) }) {
                val blocco = LinkedHashSet<Forma>()
                blocco += b
                blocco += sopra
                blocco += sotto
                out += blocco
                presi += blocco
            }
        }
        return out
    }

    /**
     * I [candidati] raggiungibili dalla [barra] (o dai [presi]) passando per tratti vicini in
     * verticale: fino a [Colonna.DALLA_BARRA] dalla barra, [Colonna.TRA_TRATTI] tra un tratto e l'altro.
     */
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
                val vicino = legati.any {
                    val passo = if (it === barra) Colonna.DALLA_BARRA else Colonna.TRA_TRATTI
                    distanzaY(it, c) <= passo * h && sovrapposizioneX(it, c) > -0.3f * h
                }
                if (vicino) {
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

/**
 * La colonna di una barra di frazione, uguale per la raccolta e per l'analisi della struttura.
 * A mano la barra viene spesso corta o spostata rispetto alle cifre, e numeratore e
 * denominatore stanno piu' o meno staccati: la colonna e' un po' piu' larga della barra.
 */
internal object Colonna {
    /** Distanza massima tra la barra e il primo tratto sopra o sotto, in altezze dei simboli. */
    const val DALLA_BARRA = 1.0f

    /**
     * La stessa distanza nell'analisi della struttura, dove i tratti sono gia' solo quelli della
     * formula (la raccolta ha gia' escluso le altre righe) e l'altezza tipica si stima su tutta la
     * formula, non solo vicino all'uguale: si puo' essere piu' larghi.
     */
    const val DALLA_BARRA_STRUTTURA = 1.3f

    /** Distanza massima tra due tratti dello stesso numeratore o denominatore. */
    const val TRA_TRATTI = 0.7f

    private fun margine(b: Forma, h: Float) = minOf(0.2f * b.larghezza, 0.4f * h) + 0.25f * h

    /** [f] ha il centro nella colonna di [b] e non ne esce molto. */
    fun dentro(b: Forma, f: Forma, h: Float): Boolean {
        val m = margine(b, h)
        return f !== b && f.altezza <= 3f * h && f.cx in b.sx - m..b.dx + m &&
            f.sx >= b.sx - m - 0.3f * h && f.dx <= b.dx + m + 0.3f * h
    }

    /**
     * Sopra la barra: il centro sta piu' in alto della barra e il tratto non la attraversa. I
     * simboli della riga accanto alla barra (il piu', le cifre) stanno alla sua altezza e restano fuori.
     */
    fun sopra(b: Forma, f: Forma, h: Float) = f.cy < b.alto && f.basso <= b.basso + 0.15f * h

    /** Sotto la barra, come [sopra]. */
    fun sotto(b: Forma, f: Forma, h: Float) = f.cy > b.basso && f.alto >= b.alto - 0.15f * h
}
