package it.frumorn.tratto.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.ProgressDomain
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

    /**
     * Quanto la pressione cambia lo spessore: 0,6 leggera, 1 media, 1,5 forte.
     * Sul Tab S6 Lite la penna manda tutta la scala 0..1 (tratti leggeri ~0,1, decisi ~0,9).
     */
    @Volatile var sensibilita: Float = 1f
        set(v) { field = v.coerceIn(0.4f, 1.8f) }

    private fun comportamento(
        sorgente: SourceNode.Source,
        daSorgente: Float,
        aSorgente: Float,
        bersaglio: TargetNode.Target,
        da: Float,
        a: Float,
        curva: EasingFunction = EasingFunction.Predefined.LINEAR,
        smorzamento: Float = 0f,
    ): BrushBehavior {
        var nodo: androidx.ink.brush.behavior.ValueNode = ResponseNode(curva, SourceNode(sorgente, daSorgente, aSorgente))
        if (smorzamento > 0f) nodo = DampingNode(ProgressDomain.TIME_IN_SECONDS, smorzamento, nodo)
        return BrushBehavior(TargetNode(bersaglio, da, a, nodo))
    }

    /** Spessore in funzione della pressione, allargato o ristretto dalla sensibilita'. */
    private fun pressioneSuSpessore(min: Float, max: Float, s: Float) = comportamento(
        SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, TargetNode.Target.SIZE_MULTIPLIER,
        1f - (1f - min) * s, 1f + (max - 1f) * s,
        EasingFunction.Predefined.EASE_OUT, smorzamento = 0.012f,
    )

    private fun crea(penna: Penna, s: Float): BrushFamily = when (penna) {
        // Penna a sfera: da un filo a una volta e mezzo lo spessore.
        Penna.PENNA -> BrushFamily(
            tip = BrushTip(behaviors = listOf(pressioneSuSpessore(0.34f, 1.45f, s))),
            clientBrushFamilyId = "tratto/penna",
        )
        // Stilografica: pennino piatto a 45 gradi, pieni e filini dipendono dalla direzione.
        // Il taglio va come "/": pieno scendendo verso destra, filo salendo, come una penna vera
        // (con +45 era a specchio, perche' sullo schermo la y cresce verso il basso). Il filo non
        // scende sotto il 40% e la pressione conta meno che nella penna a sfera: a penna molto
        // inclinata il tablet puo' dare pressione zero per tutto il tratto, che spariva.
        //
        // Sotto il pennino c'e' un secondo strato tondo e sottile: un filo piu' stretto di un pixel
        // si disegnava a pezzi (salendo in diagonale con la penna leggera sembrava un tratto
        // vuoto), cosi' invece resta sempre continuo.
        Penna.STILOGRAFICA -> BrushFamily(
            coats = listOf(
                BrushCoat(BrushTip(scaleX = 0.34f, scaleY = 0.34f), BrushPaint()),
                BrushCoat(
                    BrushTip(
                        scaleX = 1f, scaleY = 0.4f, cornerRounding = 0.6f, rotationDegrees = -45f,
                        behaviors = listOf(pressioneSuSpessore(0.55f, 1.35f, s)),
                    ),
                    BrushPaint(),
                ),
            ),
            clientBrushFamilyId = "tratto/stilografica",
        )
        // Matita: piu' si preme piu' e' scura; inclinandola il tratto si allarga e schiarisce.
        Penna.MATITA -> BrushFamily(
            tip = BrushTip(
                behaviors = listOf(
                    comportamento(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f, TargetNode.Target.OPACITY_MULTIPLIER,
                        1f - 0.8f * s.coerceAtMost(1.2f), 1f, EasingFunction.Predefined.EASE_OUT, 0.012f),
                    pressioneSuSpessore(0.7f, 1.15f, s),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.35f, 1.2f, TargetNode.Target.SIZE_MULTIPLIER, 1f, 2.6f),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.35f, 1.2f, TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0.55f),
                ),
            ),
            paint = BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE),
            clientBrushFamilyId = "tratto/matita",
        )
        // Pennello: grande escursione con la pressione, piu' largo se inclinato.
        Penna.PENNELLO -> BrushFamily(
            tip = BrushTip(
                scaleX = 1f, scaleY = 0.8f,
                behaviors = listOf(
                    pressioneSuSpessore(0.1f, 1.7f, s),
                    comportamento(SourceNode.Source.TILT_IN_RADIANS, 0.3f, 1.1f, TargetNode.Target.WIDTH_MULTIPLIER, 1f, 1.8f),
                ),
            ),
            clientBrushFamilyId = "tratto/pennello",
        )
        // Evidenziatore: come su Samsung Notes non dipende dalla pressione.
        Penna.EVIDENZIATORE -> StockBrushes.highlighter(SelfOverlap.DISCARD)
    }

    private val famiglie = HashMap<Pair<Penna, Float>, BrushFamily>()

    fun famiglia(penna: Penna): BrushFamily = synchronized(famiglie) {
        famiglie.getOrPut(penna to sensibilita) { crea(penna, sensibilita) }
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
