package it.frumorn.tratto.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto

/**
 * I pennelli di Tratto costruiti con Jetpack Ink.
 *
 * Le coordinate dei tratti sono in unita' di pagina (una pagina A4 e' larga 1000 unita', quindi
 * 1 unita' = 0,021 cm). La pressione della S Pen arriva normalizzata 0..1, l'inclinazione in
 * radianti (0 = penna perpendicolare allo schermo).
 */
@OptIn(ExperimentalInkCustomBrushApi::class)
object Pennelli {
    /** Lunghezza in cm di un'unita' di pagina: serve a Ink per i comportamenti basati sulla distanza. */
    const val CM_PER_UNITA = 0.021f

    private fun comportamento(
        sorgente: SourceNode.Source,
        daSorgente: Float,
        aSorgente: Float,
        bersaglio: TargetNode.Target,
        da: Float,
        a: Float,
        curva: EasingFunction = EasingFunction.Predefined.LINEAR,
    ) = BrushBehavior(
        TargetNode(bersaglio, da, a, ResponseNode(curva, SourceNode(sorgente, daSorgente, aSorgente))),
    )

    private fun pressioneSuSpessore(da: Float, a: Float) = comportamento(
        SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, TargetNode.Target.SIZE_MULTIPLIER, da, a,
        EasingFunction.Predefined.EASE_OUT,
    )

    /** Stilografica: pennino piatto a 45 gradi, i pieni e i filini dipendono dalla direzione. */
    private val stilografica by lazy {
        BrushFamily(
            tip = BrushTip(
                scaleX = 1f, scaleY = 0.32f, cornerRounding = 0.6f, rotationDegrees = 45f,
                behaviors = listOf(pressioneSuSpessore(0.55f, 1.2f)),
            ),
            clientBrushFamilyId = "tratto/stilografica",
        )
    }

    /** Matita: piu' si preme piu' e' scura; inclinandola il tratto si allarga e schiarisce. */
    private val matita by lazy {
        BrushFamily(
            tip = BrushTip(
                behaviors = listOf(
                    comportamento(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, TargetNode.Target.OPACITY_MULTIPLIER, 0.35f, 1f),
                    comportamento(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, TargetNode.Target.SIZE_MULTIPLIER, 0.8f, 1.1f),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.35f, 1.2f, TargetNode.Target.SIZE_MULTIPLIER, 1f, 2.6f),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.35f, 1.2f, TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0.55f),
                ),
            ),
            paint = BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE),
            clientBrushFamilyId = "tratto/matita",
        )
    }

    /** Pennello: grande escursione di spessore con la pressione, piu' largo se inclinato. */
    private val pennello by lazy {
        BrushFamily(
            tip = BrushTip(
                scaleX = 1f, scaleY = 0.8f,
                behaviors = listOf(
                    pressioneSuSpessore(0.12f, 1.5f),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.3f, 1.1f, TargetNode.Target.WIDTH_MULTIPLIER, 1f, 1.8f),
                ),
            ),
            clientBrushFamilyId = "tratto/pennello",
        )
    }

    fun famiglia(penna: Penna): BrushFamily = when (penna) {
        Penna.STILOGRAFICA -> stilografica
        Penna.PENNA -> StockBrushes.pressurePen()
        Penna.MATITA -> matita
        Penna.EVIDENZIATORE -> StockBrushes.highlighter(SelfOverlap.DISCARD)
        Penna.PENNELLO -> pennello
    }

    /** L'evidenziatore e' semitrasparente: il colore scelto viene applicato al 40%. */
    fun coloreEffettivo(penna: Penna, argb: Int): Int =
        if (penna == Penna.EVIDENZIATORE) (argb and 0x00FFFFFF) or (0x66 shl 24) else argb

    fun brush(penna: Penna, argb: Int, spessore: Float): Brush =
        Brush.createWithColorIntArgb(famiglia(penna), coloreEffettivo(penna, argb), spessore, 0.08f)

    /** Ricostruisce il tratto di Ink (con la sua geometria) dai punti salvati su disco. */
    fun stroke(t: Tratto): Stroke? {
        val batch = MutableStrokeInputBatch()
        val p = t.punti
        var ultimoT = -1L
        var ux = Float.NaN
        var uy = Float.NaN
        for (i in 0 until t.quanti) {
            val o = i * Tratto.CAMPI
            val x = p[o]; val y = p[o + 1]
            val ms = p[o + 5].toLong().coerceAtLeast(ultimoT)
            if (x == ux && y == uy && ms == ultimoT) continue
            try {
                batch.add(
                    InputToolType.STYLUS, x, y, ms, CM_PER_UNITA,
                    p[o + 2].coerceIn(0f, 1f),
                    if (p[o + 3] >= 0f) p[o + 3].coerceAtMost(1.5707f) else StrokeInput.NO_TILT,
                    if (p[o + 4] >= 0f) p[o + 4] else StrokeInput.NO_ORIENTATION,
                )
                ultimoT = ms; ux = x; uy = y
            } catch (_: IllegalArgumentException) {
                // Punto non valido (fuori ordine o duplicato): lo saltiamo come fa la penna reale.
            }
        }
        if (batch.isEmpty()) return null
        return Stroke(brush(t.penna, t.colore, t.spessore), batch)
    }

    /** Estrae i punti da un tratto appena finito per salvarlo nel nostro formato. */
    fun punti(stroke: Stroke): FloatArray {
        val inputs = stroke.inputs
        val out = FloatArray(inputs.size * Tratto.CAMPI)
        val s = StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, s)
            val o = i * Tratto.CAMPI
            out[o] = s.x
            out[o + 1] = s.y
            out[o + 2] = if (s.hasPressure) s.pressure else 0.5f
            out[o + 3] = if (s.hasTilt) s.tiltRadians else -1f
            out[o + 4] = if (s.hasOrientation) s.orientationRadians else -1f
            out[o + 5] = s.elapsedTimeMillis.toFloat()
        }
        return out
    }
}
