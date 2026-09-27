package it.frumorn.tratto.calcolo

import java.text.Normalizer

/**
 * Dal testo che ML Kit legge in un pezzo di formula al testo canonico di [Espressione]: cifre
 * con il punto decimale, + - * / ^ ( ) % √ π.
 *
 * ML Kit non ha un modello per la matematica: il modello italiano legge bene cifre e segni, ma
 * a volte scambia una cifra per una lettera che le somiglia. La lettura normale accetta solo
 * cifre e segni (x e × per il per, : e ÷ per il diviso, virgola o punto per i decimali); quella
 * tollerante converte anche le lettere tipiche (O -> 0, l -> 1, S -> 5, t -> +...). Un testo con
 * altre lettere non e' una formula: e' testo normale che finisce con un uguale.
 */
internal object Testo {
    /** Numero massimo di letture diverse di un pezzo da provare. */
    private const val VARIANTI_MAX = 12

    private const val APICI = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁽⁾"
    private const val NORMALI = "0123456789+-()"

    fun normalizza(grezzo: String, tollerante: Boolean): String? {
        val s = Normalizer.normalize(grezzo, Normalizer.Form.NFC).trim().removeSuffix("=").trim()
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Esponenti gia' in apice ("2³"): diventano ^(3).
            if (c in APICI) {
                out.append("^(")
                while (i < s.length && s[i] in APICI) out.append(NORMALI[APICI.indexOf(s[i++])])
                out.append(')')
                continue
            }
            if ((c == 'p' || c == 'P') && (s.getOrNull(i + 1) == 'i' || s.getOrNull(i + 1) == 'I')) {
                out.append('π')
                i += 2
                continue
            }
            out.append(simbolo(c, tollerante) ?: return null)
            i++
        }
        val t = out.toString().replace(Regex(" +"), " ").trim()
        return t.ifEmpty { null }
    }

    private fun simbolo(c: Char, tollerante: Boolean): Char? = when {
        c.isWhitespace() -> ' '
        c in '0'..'9' -> c
        c == '+' -> '+'
        c in "-−–—‐‑‒﹣－" -> '-'
        c in "xX×*✕✖⨯·•∙⋅" -> '*'
        c in "÷:/∕⁄" -> '/'
        c == ',' || c == '.' -> '.'
        c in "([{" -> '('
        c in ")]}" -> ')'
        c == '^' -> '^'
        c in "√✓✔" -> '√'
        c == 'π' -> 'π'
        c == '%' -> '%'
        tollerante -> when (c) {
            'O', 'o', 'D', 'Q', '°' -> '0'
            'l', 'I', 'i', '|', '!' -> '1'
            'Z', 'z' -> '2'
            'S', 's', '$' -> '5'
            'B' -> '8'
            'g', 'q' -> '9'
            'b', 'G' -> '6'
            'T', 't' -> '+'
            else -> null
        }
        else -> null
    }

    /**
     * Le letture da provare per un pezzo, dalla piu' probabile: i candidati di ML Kit normali,
     * poi tolleranti, poi senza spazi. Per il primo pezzo della formula ([iniziale]) anche le
     * code dei candidati, perche' la formula puo' seguire del testo sulla stessa riga
     * ("Totale: 12+7" -> "12+7").
     */
    fun varianti(candidati: List<String>, iniziale: Boolean): List<String> {
        val out = LinkedHashSet<String>()
        for (c in candidati) normalizza(c, false)?.let { out += it }
        for (c in candidati) normalizza(c, true)?.let { out += it }
        if (iniziale) for (c in candidati) out += code(c)
        for (c in candidati) normalizza(c, false)?.replace(" ", "")?.let { out += it }
        return out.take(VARIANTI_MAX)
    }

    /** Le parti finali di [grezzo] che sono una formula: dopo uno spazio o dopo l'ultima lettera. */
    private fun code(grezzo: String): List<String> {
        val out = ArrayList<String>()
        fun prova(s: String) {
            for (tollerante in listOf(false, true)) {
                val n = normalizza(s, tollerante)?.trimStart { it in "*/^.%) " }
                if (!n.isNullOrEmpty()) out += n
            }
        }
        val pezzi = grezzo.trim().split(Regex("\\s+"))
        for (k in pezzi.size - 1 downTo 1) prova(pezzi.takeLast(k).joinToString(" "))
        val ultimo = grezzo.indexOfLast { !it.isWhitespace() && simbolo(it, false) == null && it !in APICI }
        if (ultimo >= 0) prova(grezzo.substring(ultimo + 1))
        return out
    }
}
