package it.frumorn.tratto

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import it.frumorn.tratto.data.Archive
import it.frumorn.tratto.editor.Typefaces

/**
 * Applicazione: tiene l'archivio delle note, creato al primo uso e condiviso da tutte le schermate.
 *
 * E' anche il Configuration.Provider di WorkManager (backup automatico e, nella versione completa,
 * download del modello di ML Kit), che cosi' si avvia solo quando serve invece che a ogni avvio dell'app.
 */
class TrattoApp : Application(), Configuration.Provider {
    val archivio: Archive by lazy { Archive(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Solo il riferimento al contesto: i caratteri delle caselle di testo si caricano al primo uso.
        Typefaces.prepara(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    companion object {
        lateinit var instance: TrattoApp
            private set

        fun archivio(context: Context): Archive = (context.applicationContext as TrattoApp).archivio
    }
}
