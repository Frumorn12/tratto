package it.frumorn.tratto.calcolo

/**
 * Albero di un'espressione numerica, con il suo valore e la sua trascrizione leggibile.
 *
 * Il testo in ingresso e' quello canonico prodotto da [Testo] e dalla struttura della formula:
 * cifre con il punto decimale, + - * / ^ ( ) % √ π e "⁄" (U+2044) per la barra di frazione,
 * che si calcola come la divisione ma si trascrive diversa.
 */
internal sealed class Nodo {
    /** Il valore, o null se il calcolo e' impossibile (divisione per zero, radice di un negativo). */
    abstract fun valuta(): Numero?

    /** Vero se c'e' davvero un calcolo: "5" o "-5" da soli non meritano un risultato. */
    abstract val operazione: Boolean

    /** L'espressione all'italiana: × per, : diviso, / frazione, − meno, virgola decimale. */
    fun stampa(): String = Espressione.stampa(this)
}

internal class Letterale(val valore: Numero.Esatto) : Nodo() {
    override fun valuta(): Numero = valore
    override val operazione: Boolean get() = false
}

internal object PiGreco : Nodo() {
    override fun valuta(): Numero = Numero.PI
    override val operazione: Boolean get() = true
}

/** Operazione tra due termini: + - * / ^ e ⁄ (frazione). */
internal class Binario(val op: Char, val a: Nodo, val b: Nodo) : Nodo() {
    override fun valuta(): Numero? {
        val x = a.valuta() ?: return null
        val y = b.valuta() ?: return null
        return when (op) {
            '+' -> Numero.somma(x, y)
            '-' -> Numero.differenza(x, y)
            '*' -> Numero.prodotto(x, y)
            '/', '⁄' -> Numero.quoziente(x, y)
            '^' -> Numero.potenza(x, y)
            else -> null
        }
    }

    override val operazione: Boolean get() = true
}

internal class Opposto(val a: Nodo) : Nodo() {
    override fun valuta(): Numero? = a.valuta()?.let { Numero.opposto(it) }
    override val operazione: Boolean get() = a.operazione
}

internal class RadiceQuadrata(val a: Nodo) : Nodo() {
    override fun valuta(): Numero? = a.valuta()?.let { Numero.radice(it) }
    override val operazione: Boolean get() = true
}

internal class Percento(val a: Nodo) : Nodo() {
    override fun valuta(): Numero? = a.valuta()?.let { Numero.percento(it) }
    override val operazione: Boolean get() = true
}

internal object Espressione {
    private sealed interface Token
    private class Num(val testo: String) : Token
    private class Op(val c: Char) : Token
    private data object Apri : Token
    private data object Chiudi : Token
    private data object Pi : Token
    private data object Rad : Token

    /** L'albero del testo canonico, o null se non e' un'espressione numerica valida. */
    fun analizza(testo: String): Nodo? {
        val token = tokenizza(testo) ?: return null
        if (token.isEmpty()) return null
        val p = Lettura(token)
        val n = p.espressione() ?: return null
        return if (p.finito) n else null
    }

    private fun tokenizza(testo: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < testo.length) {
            val c = testo[i]
            when {
                c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    val inizio = i
                    while (i < testo.length && (testo[i].isDigit() || testo[i] == '.')) i++
                    out += Num(testo.substring(inizio, i))
                }
                c in "+-*/⁄^%" -> { out += Op(c); i++ }
                c == '(' -> { out += Apri; i++ }
                c == ')' -> { out += Chiudi; i++ }
                c == 'π' -> { out += Pi; i++ }
                c == '√' -> { out += Rad; i++ }
                else -> return null
            }
        }
        return out
    }

    /**
     * Discesa ricorsiva con le precedenze solite: + - < * / ⁄ e prodotto implicito < meno unario
     * < ^ (associativo a destra, -2^2 = -4) < % < numeri, π, parentesi, √.
     * Prodotto implicito prima di una parentesi, di π o di una radice: 2(3+4), 2π, 3√2, (1+2)(3+4).
     */
    private class Lettura(val t: List<Token>) {
        var i = 0
        val finito: Boolean get() = i == t.size
        private fun guarda(): Token? = t.getOrNull(i)
        private fun op(c: String): Char? = (guarda() as? Op)?.c?.takeIf { it in c }

        fun espressione(): Nodo? {
            var a = termine() ?: return null
            while (true) {
                val c = op("+-") ?: break
                i++
                a = Binario(c, a, termine() ?: return null)
            }
            return a
        }

        fun termine(): Nodo? {
            var a = unario() ?: return null
            while (true) {
                val c = op("*/⁄")
                if (c != null) {
                    i++
                    a = Binario(c, a, unario() ?: return null)
                } else if (implicito()) {
                    a = Binario('*', a, potenza() ?: return null)
                } else break
            }
            return a
        }

        private fun implicito(): Boolean {
            val g = guarda() ?: return false
            val prima = t.getOrNull(i - 1)
            return g == Apri || g == Pi || g == Rad || (g is Num && prima == Chiudi)
        }

        fun unario(): Nodo? {
            if (op("-") != null) {
                i++
                return Opposto(unario() ?: return null)
            }
            return potenza()
        }

        fun potenza(): Nodo? {
            val base = postfisso() ?: return null
            if (op("^") != null) {
                i++
                return Binario('^', base, unario() ?: return null)
            }
            return base
        }

        private fun postfisso(): Nodo? {
            var a = primario() ?: return null
            while (op("%") != null) {
                i++
                a = Percento(a)
            }
            return a
        }

        private fun primario(): Nodo? = when (val g = t.getOrNull(i++)) {
            is Num -> Numero.leggi(g.testo)?.let { Letterale(it) }
            Pi -> PiGreco
            Rad -> potenza()?.let { RadiceQuadrata(it) }
            Apri -> {
                val e = espressione()
                if (e != null && t.getOrNull(i) == Chiudi) { i++; e } else null
            }
            else -> null
        }
    }

    // --- Trascrizione --------------------------------------------------------------------------

    private fun precedenza(n: Nodo): Int = when (n) {
        is Binario -> when (n.op) {
            '+', '-' -> 1
            '*', '/', '⁄' -> 2
            else -> 4
        }
        is Opposto -> 3
        is Percento -> 5
        else -> 6
    }

    fun stampa(n: Nodo): String = when (n) {
        is Letterale -> Numero.comeTesto(n.valore)
        PiGreco -> "π"
        is Opposto -> "−" + tra(n.a, precedenza(n.a) < 3)
        is RadiceQuadrata -> "√" + tra(n.a, precedenza(n.a) < 6)
        is Percento -> tra(n.a, precedenza(n.a) < 5) + "%"
        is Binario -> {
            val p = precedenza(n)
            when (n.op) {
                '^' -> tra(n.a, precedenza(n.a) <= 4) + "^" + tra(n.b, precedenza(n.b) < 6)
                // Frazione: numeratore e denominatore composti tra parentesi, come sotto e sopra la barra.
                '⁄' -> tra(n.a, precedenza(n.a) < 5) + "/" + tra(n.b, precedenza(n.b) < 5)
                else -> {
                    val simbolo = when (n.op) {
                        '+' -> "+"
                        '-' -> "−"
                        '*' -> "×"
                        else -> ":"
                    }
                    val destra = precedenza(n.b) < p || (precedenza(n.b) == p && n.op in "-/") || n.b is Opposto
                    tra(n.a, precedenza(n.a) < p) + simbolo + tra(n.b, destra)
                }
            }
        }
    }

    private fun tra(n: Nodo, parentesi: Boolean) = if (parentesi) "(" + stampa(n) + ")" else stampa(n)
}
