package it.frumorn.tratto

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import it.frumorn.tratto.data.Archivio

/**
 * Applicazione: tiene l'archivio delle note, creato al primo uso e condiviso da tutte le schermate.
 *
 * E' anche il Configuration.Provider di WorkManager, che cosi' si avvia solo quando serve (lo usa
 * ML Kit per scaricare il modello di riconoscimento) invece che a ogni avvio dell'app.
 */
class TrattoApp : Application(), Configuration.Provider {
    val archivio: Archivio by lazy { Archivio(this) }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()

    companion object {
        fun archivio(context: Context): Archivio = (context.applicationContext as TrattoApp).archivio
    }
}
