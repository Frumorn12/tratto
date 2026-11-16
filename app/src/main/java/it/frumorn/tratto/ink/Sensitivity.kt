package it.frumorn.tratto.ink

/**
 * Quanto la pressione cambia lo spessore, con la sensibilita' scelta nelle impostazioni ([s]: 0,6
 * Leggera, 1 Media, 1,5 Forte). [min] e [max] sono i moltiplicatori della penna con la Media.
 *
 * Forte allarga l'escursione solo verso l'alto. Verso il basso non scende sotto la Media: con la
 * penna inclinata la pressione cala, e la penna a sfera arrivava all'1% dello spessore (il pennello
 * a zero), cioe' un tratto che c'era ma non si vedeva.
 */
object Sensitivity {
    fun minimo(min: Float, s: Float): Float = maxOf(1f - (1f - min) * s, min)

    fun massimo(max: Float, s: Float): Float = 1f + (max - 1f) * s
}
