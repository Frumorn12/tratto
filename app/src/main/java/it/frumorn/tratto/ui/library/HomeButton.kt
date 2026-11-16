package it.frumorn.tratto.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.frumorn.tratto.MainActivity
import it.frumorn.tratto.R
import it.frumorn.tratto.ui.testoOra

/**
 * Il selettore di file di Android, su tablet, non ha un pulsante per chiudersi.
 * Questo chip resta sopra quella schermata: toccarlo riporta Tratto in primo piano
 * e chiude il selettore.
 */
internal object HomeButton {
    private var vue: LinearLayout? = null

    fun montre(context: Context): Boolean {
        if (vue != null) return true
        val app = context.applicationContext
        return runCatching {
            val dens = app.resources.displayMetrics.density
            fun dp(v: Int) = (v * dens).toInt()
            val chip = LinearLayout(app).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(20), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = 28 * dens
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1).coerceAtLeast(1), 0xFFD9D4F5.toInt())
                }
                elevation = 12 * dens
                addView(ImageView(app).apply {
                    setImageResource(R.drawable.ic_arrow_back)
                    setColorFilter(0xFF6546F3.toInt())
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
                })
                addView(TextView(app).apply {
                    text = testoOra(R.string.home)
                    setTextColor(0xFF271E52.toInt())
                    textSize = 16f
                    setPadding(dp(8), 0, 0, 0)
                    setTypeface(typeface, Typeface.BOLD)
                })
                contentDescription = testoOra(R.string.back_to_home)
                setOnClickListener { rentre(app) }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }
            app.getSystemService(WindowManager::class.java).addView(chip, params)
            vue = chip
        }.isSuccess
    }

    fun cache(context: Context) {
        val chip = vue ?: return
        vue = null
        val wm = context.applicationContext.getSystemService(WindowManager::class.java)
        // Durante il clic il sistema rifiuta removeView: si ritenta al giro dopo.
        Handler(Looper.getMainLooper()).post { runCatching { wm.removeView(chip) } }
    }

    private fun rentre(context: Context) {
        cache(context)
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
