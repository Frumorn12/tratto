package it.frumorn.tratto.calcolo

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Un numero di una formula: esatto (frazione di interi) finche' si puo', cioe' con somme,
 * prodotti, divisioni, potenze intere e radici di quadrati perfetti; approssimato (Double) con
 * pi greco, radici irrazionali e potenze non intere. Le operazioni impossibili (divisione per
 * zero, radice di un negativo) danno null.
 */
internal sealed class Numero {
    abstract fun comeDouble(): Double
    abstract val segno: Int

    class Esatto private constructor(val n: BigInteger, val d: BigInteger) : Numero() {
        override fun comeDouble(): Double = BigDecimal(n).divide(BigDecimal(d), MathContext.DECIMAL64).toDouble()
        override val segno: Int get() = n.signum()
        val intero: Boolean get() = d == BigInteger.ONE
        override fun equals(other: Any?) = other is Esatto && other.n == n && other.d == d
        override fun hashCode() = n.hashCode() * 31 + d.hashCode()
        override fun toString() = if (intero) "$n" else "$n/$d"

        companion object {
            fun di(n: BigInteger, d: BigInteger = BigInteger.ONE): Esatto {
                require(d.signum() != 0)
                val g = n.gcd(d).let { if (it.signum() == 0) BigInteger.ONE else it }
                val s = if (d.signum() < 0) -1 else 1
                return Esatto(n.divide(g).multiply(BigInteger.valueOf(s.toLong())), d.divide(g).abs())
            }

            fun di(n: Long, d: Long = 1): Esatto = di(BigInteger.valueOf(n), BigInteger.valueOf(d))
        }
    }

    class Approssimato(val v: Double) : Numero() {
        override fun comeDouble(): Double = v
        override val segno: Int get() = if (v > 0) 1 else if (v < 0) -1 else 0
        override fun toString() = "~$v"
    }

    companion object {
        val PI: Numero = Approssimato(Math.PI)

        /** Oltre questi bit un numero esatto diventa approssimato: non si fanno conti enormi. */
        private const val BIT_MAX = 4000

        /** Un numero scritto in cifre con al piu' un punto decimale ("3.25", ".5"). */
        fun leggi(testo: String): Esatto? {
            val t = testo.trim()
            if (t.isEmpty() || t == "." || t.count { it == '.' } > 1 || t.any { it != '.' && it !in '0'..'9' }) return null
            val bd = BigDecimal(if (t.startsWith(".")) "0$t" else t)
            return if (bd.scale() > 0) Esatto.di(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale()))
            else Esatto.di(bd.toBigIntegerExact())
        }

        private fun controlla(x: Numero?): Numero? = when (x) {
            is Esatto -> if (x.n.bitLength() > BIT_MAX || x.d.bitLength() > BIT_MAX) approssimato(x.comeDouble()) else x
            is Approssimato -> approssimato(x.v)
            null -> null
        }

        private fun approssimato(v: Double): Numero? = if (v.isNaN() || v.isInfinite()) null else Approssimato(v)

        fun somma(a: Numero, b: Numero): Numero? = controlla(
            if (a is Esatto && b is Esatto) Esatto.di(a.n * b.d + b.n * a.d, a.d * b.d)
            else Approssimato(a.comeDouble() + b.comeDouble()),
        )

        fun differenza(a: Numero, b: Numero): Numero? = somma(a, opposto(b))

        fun opposto(a: Numero): Numero = when (a) {
            is Esatto -> Esatto.di(a.n.negate(), a.d)
            is Approssimato -> Approssimato(-a.v)
        }

        fun prodotto(a: Numero, b: Numero): Numero? = controlla(
            if (a is Esatto && b is Esatto) Esatto.di(a.n * b.n, a.d * b.d)
            else Approssimato(a.comeDouble() * b.comeDouble()),
        )

        fun quoziente(a: Numero, b: Numero): Numero? {
            if (b.segno == 0) return null
            return controlla(
                if (a is Esatto && b is Esatto) Esatto.di(a.n * b.d, a.d * b.n)
                else Approssimato(a.comeDouble() / b.comeDouble()),
            )
        }

        fun potenza(a: Numero, b: Numero): Numero? {
            if (a is Esatto && b is Esatto && b.intero) {
                val e = b.n
                if (a.segno == 0) return if (e.signum() < 0) null else if (e.signum() == 0) Esatto.di(1) else a
                if (a == Esatto.di(1)) return a
                if (a == Esatto.di(-1)) return if (e.testBit(0)) a else Esatto.di(1)
                val m = e.abs()
                val bit = maxOf(a.n.bitLength(), a.d.bitLength()).toLong()
                if (m.bitLength() < 31 && bit * m.toLong() <= BIT_MAX) {
                    val k = m.toInt()
                    val num = a.n.pow(k)
                    val den = a.d.pow(k)
                    return if (e.signum() >= 0) Esatto.di(num, den) else Esatto.di(den, num)
                }
                return approssimato(a.comeDouble().pow(b.comeDouble()))
            }
            // Esponente frazionario esatto p/q: radice q-esima esatta se c'e', con il segno per q dispari.
            if (a is Esatto && b is Esatto && b.d.bitLength() <= 5) {
                val q = b.d.toInt()
                if (a.segno < 0 && q % 2 == 0) return null
                val r = radiceEsatta(a, q)
                if (r != null) return potenza(r, Esatto.di(b.n))
                val base = a.comeDouble()
                val v = if (base < 0) -(-base).pow(b.comeDouble()) * (if (b.n.testBit(0)) 1 else -1) else base.pow(b.comeDouble())
                return approssimato(v)
            }
            val base = a.comeDouble()
            val esp = b.comeDouble()
            if (base == 0.0 && esp < 0) return null
            return approssimato(base.pow(esp))
        }

        fun radice(a: Numero): Numero? {
            if (a.segno < 0) return null
            if (a is Esatto) radiceEsatta(a, 2)?.let { return it }
            return approssimato(sqrt(a.comeDouble()))
        }

        fun percento(a: Numero): Numero? = quoziente(a, Esatto.di(100))

        /** Radice q-esima esatta di una frazione (numeratore e denominatore potenze perfette), o null. */
        private fun radiceEsatta(a: Esatto, q: Int): Esatto? {
            if (q < 1) return null
            if (q == 1) return a
            val negativo = a.segno < 0
            if (negativo && q % 2 == 0) return null
            val n = radiceIntera(a.n.abs(), q) ?: return null
            val d = radiceIntera(a.d, q) ?: return null
            return Esatto.di(if (negativo) n.negate() else n, d)
        }

        /** Radice q-esima intera di x >= 0 se x e' una potenza q-esima perfetta. */
        private fun radiceIntera(x: BigInteger, q: Int): BigInteger? {
            if (x.signum() == 0) return BigInteger.ZERO
            if (q == 2) {
                val r = x.sqrt()
                return if (r * r == x) r else null
            }
            // Newton sugli interi, partendo da una stima per eccesso.
            var r = BigInteger.ONE.shiftLeft(x.bitLength() / q + 1)
            val qq = BigInteger.valueOf(q.toLong())
            val q1 = BigInteger.valueOf((q - 1).toLong())
            while (true) {
                val prossimo = (r * q1 + x / r.pow(q - 1)) / qq
                if (prossimo >= r) break
                r = prossimo
            }
            return if (r.pow(q) == x) r else null
        }

        // --- Scrittura ---------------------------------------------------------------------------

        /** Oltre questo valore (13 cifre) si passa alla notazione scientifica. */
        private val GRANDE = BigDecimal("1e12")
        private val PICCOLO = BigDecimal("1e-6")

        /**
         * Il numero all'italiana: virgola decimale, niente separatore delle migliaia, al piu' 6
         * decimali (6 cifre significative sotto l'1), niente zeri finali. Oltre 12 cifre intere o
         * sotto un milionesimo: notazione scientifica con la "e" (1,234568e15).
         */
        fun formatta(x: Numero): String {
            val bd = when (x) {
                is Esatto -> if (x.intero) BigDecimal(x.n) else BigDecimal(x.n).divide(BigDecimal(x.d), MathContext(40, RoundingMode.HALF_EVEN))
                // Un Double ha circa 16 cifre buone: si tolgono le ultime, cosi' 2,0000000000000004
                // diventa 2; e un residuo minuscolo (√2·√2 - 2) e' zero, non 4,4e-16.
                is Approssimato -> if (abs(x.v) < 1e-10) return "0" else BigDecimal(x.v).round(MathContext(13, RoundingMode.HALF_EVEN))
            }
            return formatta(bd)
        }

        fun formatta(x: BigDecimal): String {
            if (x.signum() == 0) return "0"
            val a = x.abs()
            if (a >= GRANDE || a < PICCOLO) return scientifica(x)
            val arrotondato = if (a >= BigDecimal.ONE) x.setScale(6, RoundingMode.HALF_UP) else x.round(MathContext(6, RoundingMode.HALF_UP))
            if (arrotondato.signum() == 0) return "0"
            if (arrotondato.abs() >= GRANDE) return scientifica(x)
            return arrotondato.stripTrailingZeros().toPlainString().replace('.', ',')
        }

        private fun scientifica(x: BigDecimal): String {
            var mantissa = x.round(MathContext(7, RoundingMode.HALF_UP))
            var esponente = mantissa.precision() - mantissa.scale() - 1
            mantissa = mantissa.movePointLeft(esponente)
            if (mantissa.abs() >= BigDecimal.TEN) { mantissa = mantissa.movePointLeft(1); esponente++ }
            val m = mantissa.stripTrailingZeros().toPlainString().replace('.', ',')
            return "${m}e$esponente"
        }

        /** Il numero come compare nell'espressione trascritta: virgola decimale, senza arrotondare. */
        fun comeTesto(x: Esatto): String {
            if (x.intero) return x.n.toString()
            val bd = BigDecimal(x.n).divide(BigDecimal(x.d), MathContext(40, RoundingMode.HALF_EVEN))
            return bd.stripTrailingZeros().toPlainString().replace('.', ',')
        }
    }
}
