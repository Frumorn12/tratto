package it.frumorn.tratto.editor

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Carattere

/**
 * I caratteri delle caselle di testo. Manrope e' quello dell'app, con i suoi quattro pesi in un'unica
 * famiglia: il grassetto usa il file bold vero invece di ispessire il regular. Si carica al primo uso,
 * non all'avvio.
 */
object Caratteri {
    @Volatile private var contesto: Context? = null

    /** Da chiamare una volta (Application): serve solo a trovare i file dei caratteri quando servono. */
    fun prepara(context: Context) {
        contesto = context.applicationContext
    }

    private val manrope: Typeface by lazy {
        val c = contesto ?: return@lazy Typeface.SANS_SERIF
        try {
            val r = c.resources
            val famiglia = FontFamily.Builder(Font.Builder(r, R.font.manrope_regular).setWeight(400).build())
                .addFont(Font.Builder(r, R.font.manrope_medium).setWeight(500).build())
                .addFont(Font.Builder(r, R.font.manrope_semibold).setWeight(600).build())
                .addFont(Font.Builder(r, R.font.manrope_bold).setWeight(700).build())
                .build()
            Typeface.CustomFallbackBuilder(famiglia).setSystemFallback("sans-serif").build()
        } catch (e: Exception) {
            Log.w("Caratteri", "Manrope non disponibile", e)
            Typeface.SANS_SERIF
        }
    }

    private val cache = HashMap<Int, Typeface>()

    fun base(c: Carattere): Typeface = when (c) {
        Carattere.MANROPE -> manrope
        Carattere.SERIF -> Typeface.SERIF
        Carattere.MONO -> Typeface.MONOSPACE
    }

    /** Il carattere [c] con il peso e lo stile chiesti (il corsivo di Manrope e' inclinato dal sistema). */
    fun di(c: Carattere, grassetto: Boolean, corsivo: Boolean): Typeface {
        val chiave = c.ordinal * 4 + (if (grassetto) 2 else 0) + (if (corsivo) 1 else 0)
        synchronized(cache) {
            return cache.getOrPut(chiave) { Typeface.create(base(c), if (grassetto) 700 else 400, corsivo) }
        }
    }
}
