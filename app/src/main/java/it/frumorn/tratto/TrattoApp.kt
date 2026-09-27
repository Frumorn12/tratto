package it.frumorn.tratto

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import it.frumorn.tratto.data.Archivio

/** Applicazione: tiene l'archivio delle note, creato al primo uso e condiviso da tutte le schermate. */
class TrattoApp : Application(), Configuration.Provider {
    val archivio: Archivio by lazy { Archivio(this) }

    /**
     * WorkManager (backup su Drive) si inizializza al primo uso e non all'avvio: il suo inizializzatore
     * automatico e' tolto nel manifest.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    companion object {
        fun archivio(context: Context): Archivio = (context.applicationContext as TrattoApp).archivio
    }
}
