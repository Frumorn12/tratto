package it.frumorn.tratto.editor

import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.Tratto
import kotlin.math.max
import kotlin.math.min

/**
 * Rotation d'une page de [quarti] × 90° dans le sens horaire (négatif = antihoraire).
 * Les coordonnées restent dans le repère page : (0,0) en haut à gauche après rotation.
 */
internal fun ruotaPunto(x: Float, y: Float, larghezza: Float, altezza: Float, quarti: Int): Pair<Float, Float> {
    var qx = x
    var qy = y
    var w = larghezza
    var h = altezza
    val n = ((quarti % 4) + 4) % 4
    repeat(n) {
        val nx = h - qy
        val ny = qx
        qx = nx
        qy = ny
        val t = w
        w = h
        h = t
    }
    return qx to qy
}

internal fun dimensioniDopoRotazione(larghezza: Float, altezza: Float, quarti: Int): Pair<Float, Float> {
    val n = ((quarti % 4) + 4) % 4
    return if (n % 2 == 0) larghezza to altezza else altezza to larghezza
}

internal fun ruotaTratto(t: Tratto, larghezza: Float, altezza: Float, quarti: Int): Tratto {
    val n = ((quarti % 4) + 4) % 4
    if (n == 0) return t
    val p = t.punti.copyOf()
    var i = 0
    while (i + 1 < p.size) {
        val (nx, ny) = ruotaPunto(p[i], p[i + 1], larghezza, altezza, quarti)
        p[i] = nx
        p[i + 1] = ny
        i += Tratto.CAMPI
    }
    return Tratto(t.id, t.penna, t.colore, t.spessore, p)
}

internal fun ruotaOggetto(o: Oggetto, larghezza: Float, altezza: Float, quarti: Int): Oggetto {
    val n = ((quarti % 4) + 4) % 4
    if (n == 0) return o
    return when (o) {
        is Immagine -> {
            val (x, y, w, h) = ruotaRettangolo(o.x, o.y, o.larghezza, o.altezza, larghezza, altezza, quarti)
            o.copy(x = x, y = y, larghezza = w, altezza = h)
        }
        is Testo -> {
            // Hauteur de boîte inconnue : on fait tourner l'origine et on garde la largeur utile.
            val (nx, ny) = ruotaPunto(o.x, o.y, larghezza, altezza, quarti)
            o.copy(x = nx, y = ny)
        }
    }
}

internal fun ruotaRettangolo(
    x: Float, y: Float, w: Float, h: Float,
    paginaW: Float, paginaH: Float, quarti: Int,
): FloatArray {
    val coins = arrayOf(
        ruotaPunto(x, y, paginaW, paginaH, quarti),
        ruotaPunto(x + w, y, paginaW, paginaH, quarti),
        ruotaPunto(x, y + h, paginaW, paginaH, quarti),
        ruotaPunto(x + w, y + h, paginaW, paginaH, quarti),
    )
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for ((px, py) in coins) {
        minX = min(minX, px)
        minY = min(minY, py)
        maxX = max(maxX, px)
        maxY = max(maxY, py)
    }
    return floatArrayOf(minX, minY, maxX - minX, maxY - minY)
}

internal fun ruotaPaginaMeta(pagina: Pagina, quarti: Int): Pagina {
    val (w, h) = dimensioniDopoRotazione(pagina.larghezza, pagina.altezza, quarti)
    return pagina.copy(larghezza = w, altezza = h)
}
