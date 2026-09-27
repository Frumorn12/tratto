package it.frumorn.tratto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.pdf.PdfSfondo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Disegna le pagine della nota una sotto l'altra, con scorrimento e zoom.
 *
 * Coordinate "mondo": ogni pagina e' larga 1000 unita'; le pagine sono impilate in verticale con
 * uno spazio di [SPAZIO] unita'. Lo schermo si ottiene con x * scala + tx, y * scala + ty.
 *
 * I tratti finiti di ogni pagina sono registrati in un RenderNode alla scala corrente: durante lo
 * scorrimento si ridisegna solo il nodo (costo quasi nullo), e il nodo si rifa' quando la pagina
 * cambia o quando lo zoom si ferma su una scala diversa.
 */
class FoglioView(context: Context) : View(context) {
    var documento: Documento? = null
        set(v) { field = v; cache.clear(); requestLayout(); invalidate() }
    var pdf: PdfSfondo? = null

    var scala = 1f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set
    var pizzicando = false
        set(v) { field = v; if (!v) invalidate() }

    var colorePagina = Color.WHITE
    var coloreScrivania = Color.rgb(242, 242, 244)
    var coloreRighe = Color.rgb(217, 214, 236)
    /** Tratti da non disegnare perche' li sta mostrando qualcun altro (es. il lazo mentre li sposta). */
    var nascosti: Set<Long> = emptySet()
        set(v) { field = v; cache.values.forEach { it.versione = -1 }; invalidate() }

    /** Chiamato quando cambia la pagina visibile o la trasformazione. */
    var alMovimento: (() -> Unit)? = null

    private val renderer = CanvasStrokeRenderer.create()
    private val densita = resources.displayMetrics.density
    private val pCarta = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(10f * densita, 0f, 2f * densita, Color.argb(40, 31, 23, 71)) }
    private val pRighe = Paint().apply { strokeWidth = 0f }
    private val pPunti = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pBitmap = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rett = RectF()
    private val m = Matrix()

    private class CachePagina(val nodo: RenderNode) {
        var versione = -1
        var scala = 0f
    }
    private val cache = HashMap<PaginaViva, CachePagina>()

    /** Posizione verticale (in unita' mondo) dell'inizio di ogni pagina. */
    fun cimaPagina(i: Int): Float {
        val d = documento ?: return 0f
        var y = MARGINE_ALTO
        for (k in 0 until i) y += d.pagine[k].pagina.altezza + SPAZIO
        return y
    }

    fun altezzaTotale(): Float {
        val d = documento ?: return 0f
        return MARGINE_ALTO + d.pagine.sumOf { (it.pagina.altezza + SPAZIO).toDouble() }.toFloat() + MARGINE_BASSO
    }

    /** Scala che fa entrare la pagina in larghezza, con un piccolo margine. */
    fun scalaAdatta(): Float {
        if (width == 0) return 1f
        val margine = if (width > height) 0.18f else 0.035f
        return width * (1f - 2 * margine) / LARGHEZZA
    }

    fun scalaMin() = scalaAdatta() * 0.5f
    fun scalaMax() = scalaAdatta() * 5f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw == 0) {
            scala = scalaAdatta()
            tx = (w - LARGHEZZA * scala) / 2f
            ty = 0f
        } else {
            // Mantiene il punto al centro dello schermo dopo una rotazione.
            val cx = (oldw / 2f - tx) / scala
            val cy = (oldh / 2f - ty) / scala
            scala = scalaAdatta()
            tx = w / 2f - cx * scala
            ty = h / 2f - cy * scala
        }
        limita()
    }

    fun trasla(dx: Float, dy: Float) {
        tx += dx; ty += dy
        limita()
        invalidate()
        alMovimento?.invoke()
    }

    fun zoom(fattore: Float, fx: Float, fy: Float) {
        val nuova = (scala * fattore).coerceIn(scalaMin(), scalaMax())
        val f = nuova / scala
        tx = fx - (fx - tx) * f
        ty = fy - (fy - ty) * f
        scala = nuova
        limita()
        invalidate()
        alMovimento?.invoke()
    }

    fun impostaVista(s: Float, x: Float, y: Float) {
        scala = s.coerceIn(scalaMin(), scalaMax()); tx = x; ty = y
        limita(); invalidate(); alMovimento?.invoke()
    }

    /** Porta in vista l'inizio della pagina [i]. */
    fun vaiAPagina(i: Int) {
        ty = -cimaPagina(i) * scala + 24 * densita
        limita(); invalidate(); alMovimento?.invoke()
    }

    /** Impedisce di perdere di vista le pagine. */
    fun limita() {
        if (width == 0) return
        val largo = LARGHEZZA * scala
        tx = if (largo <= width) (width - largo) / 2f else tx.coerceIn(width - largo - 24 * densita, 24 * densita)
        val alto = altezzaTotale() * scala
        val minTy = min(0f, height - alto)
        ty = ty.coerceIn(minTy, 0f)
    }

    val limiteTy: ClosedFloatingPointRange<Float>
        get() = min(0f, height - altezzaTotale() * scala)..0f
    val limiteTx: ClosedFloatingPointRange<Float>
        get() {
            val largo = LARGHEZZA * scala
            return if (largo <= width) ((width - largo) / 2f).let { it..it } else (width - largo - 24 * densita)..(24 * densita)
        }

    /** Indice della pagina sotto un punto dello schermo, o -1. */
    fun paginaSotto(x: Float, y: Float, tolleranza: Float = 0f): Int {
        val d = documento ?: return -1
        val wy = (y - ty) / scala
        val wx = (x - tx) / scala
        if (wx < -tolleranza || wx > LARGHEZZA + tolleranza) return -1
        var cima = MARGINE_ALTO
        for ((i, p) in d.pagine.withIndex()) {
            if (wy >= cima - tolleranza && wy <= cima + p.pagina.altezza + tolleranza) return i
            cima += p.pagina.altezza + SPAZIO
        }
        return -1
    }

    /** Pagina piu' vicina al centro dello schermo. */
    fun paginaCorrente(): Int {
        val d = documento ?: return 0
        val cy = (height / 2f - ty) / scala
        var cima = MARGINE_ALTO
        for ((i, p) in d.pagine.withIndex()) {
            if (cy < cima + p.pagina.altezza + SPAZIO / 2) return i
            cima += p.pagina.altezza + SPAZIO
        }
        return d.pagine.lastIndex
    }

    /** Matrice schermo -> coordinate della pagina [i]. */
    fun schermoAPagina(i: Int, out: Matrix = Matrix()): Matrix {
        out.setTranslate(-tx, -ty)
        out.postScale(1f / scala, 1f / scala)
        out.postTranslate(0f, -cimaPagina(i))
        return out
    }

    /** Matrice coordinate della pagina [i] -> schermo. */
    fun paginaASchermo(i: Int, out: Matrix = Matrix()): Matrix {
        out.setTranslate(0f, cimaPagina(i))
        out.postScale(scala, scala)
        out.postTranslate(tx, ty)
        return out
    }

    fun pagineVisibili(): IntRange {
        val d = documento ?: return IntRange.EMPTY
        val alto = -ty / scala
        val basso = (height - ty) / scala
        var primo = -1; var ultimo = -1
        var cima = MARGINE_ALTO
        for ((i, p) in d.pagine.withIndex()) {
            val fine = cima + p.pagina.altezza
            if (fine >= alto && cima <= basso) { if (primo < 0) primo = i; ultimo = i }
            cima = fine + SPAZIO
        }
        return if (primo < 0) IntRange.EMPTY else primo..ultimo
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(coloreScrivania)
        val d = documento ?: return
        pCarta.color = colorePagina
        pRighe.color = coloreRighe
        pPunti.color = coloreRighe
        for (i in pagineVisibili()) {
            val p = d.pagine[i]
            val cima = cimaPagina(i)
            val sx = tx
            val sy = ty + cima * scala
            val w = LARGHEZZA * scala
            val h = p.pagina.altezza * scala
            rett.set(sx, sy, sx + w, sy + h)
            canvas.drawRect(rett, pCarta)

            val s = canvas.save()
            canvas.clipRect(rett)
            val pdfPagina = p.pagina.pdfPagina
            val bmp: Bitmap? = if (pdfPagina >= 0) pdf?.immagine(pdfPagina, w.toInt()) else null
            if (bmp != null) canvas.drawBitmap(bmp, null, rett, pBitmap)
            else disegnaSfondo(canvas, p.pagina.sfondo, sx, sy, p.pagina.altezza)

            if (p.caricata) disegnaTratti(canvas, p, sx, sy)
            canvas.restoreToCount(s)
        }
    }

    private fun disegnaSfondo(c: Canvas, sfondo: Sfondo, sx: Float, sy: Float, altezza: Float) {
        when (sfondo) {
            Sfondo.BIANCO -> Unit
            Sfondo.RIGHE -> {
                var y = RIGHE_INIZIO
                while (y < altezza - 40f) {
                    val py = sy + y * scala
                    c.drawLine(sx + 60f * scala, py, sx + (LARGHEZZA - 60f) * scala, py, pRighe)
                    y += PASSO_RIGHE
                }
            }
            Sfondo.QUADRETTI -> {
                var x = PASSO_QUADRETTI
                while (x < LARGHEZZA) { c.drawLine(sx + x * scala, sy, sx + x * scala, sy + altezza * scala, pRighe); x += PASSO_QUADRETTI }
                var y = PASSO_QUADRETTI
                while (y < altezza) { c.drawLine(sx, sy + y * scala, sx + LARGHEZZA * scala, sy + y * scala, pRighe); y += PASSO_QUADRETTI }
            }
            Sfondo.PUNTINI -> {
                val r = max(1.2f * densita, 1.1f * scala)
                var y = PASSO_QUADRETTI
                while (y < altezza) {
                    var x = PASSO_QUADRETTI
                    while (x < LARGHEZZA) { c.drawCircle(sx + x * scala, sy + y * scala, r, pPunti); x += PASSO_QUADRETTI }
                    y += PASSO_QUADRETTI
                }
            }
        }
    }

    private fun disegnaTratti(canvas: Canvas, p: PaginaViva, sx: Float, sy: Float) {
        if (!canvas.isHardwareAccelerated) {
            m.setScale(scala, scala); m.postTranslate(sx, sy)
            disegnaTrattiSu(canvas, p, m)
            return
        }
        val c = cache.getOrPut(p) { CachePagina(RenderNode("pagina")) }
        val scalaDiversa = abs(c.scala - scala) > 0.001f
        if (c.versione != p.versione || (scalaDiversa && !pizzicando)) {
            val w = (LARGHEZZA * scala).toInt().coerceAtLeast(1)
            val h = (p.pagina.altezza * scala).toInt().coerceAtLeast(1)
            c.nodo.setPosition(0, 0, w, h)
            val rc = c.nodo.beginRecording(w, h)
            m.setScale(scala, scala)
            disegnaTrattiSu(rc, p, m)
            c.nodo.endRecording()
            c.versione = p.versione
            c.scala = scala
        }
        val s = canvas.save()
        canvas.translate(sx, sy)
        if (abs(c.scala - scala) > 0.001f) canvas.scale(scala / c.scala, scala / c.scala)
        canvas.drawRenderNode(c.nodo)
        canvas.restoreToCount(s)
    }

    /** Disegna i tratti della pagina con la trasformazione pagina -> canvas [t]. */
    fun disegnaTrattiSu(canvas: Canvas, p: PaginaViva, t: Matrix) {
        val nascosti = nascosti
        val s = canvas.save()
        canvas.concat(t)
        synchronized(p) {
            for (tr in p.tratti) {
                if (tr.id in nascosti) continue
                val st = p.stroke(tr) ?: continue
                renderer.draw(canvas, st, t)
            }
        }
        canvas.restoreToCount(s)
    }

    fun disegnaStroke(canvas: Canvas, stroke: androidx.ink.strokes.Stroke, t: Matrix) {
        val s = canvas.save()
        canvas.concat(t)
        renderer.draw(canvas, stroke, t)
        canvas.restoreToCount(s)
    }

    fun dimenticaCache() {
        cache.values.forEach { it.nodo.discardDisplayList() }
        cache.clear()
    }

    companion object {
        const val LARGHEZZA = 1000f
        const val SPAZIO = 36f
        const val MARGINE_ALTO = 24f
        const val MARGINE_BASSO = 220f
        const val PASSO_RIGHE = 38f
        const val RIGHE_INIZIO = 120f
        const val PASSO_QUADRETTI = 24f
    }
}
