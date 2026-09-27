package it.frumorn.tratto

import android.app.Application
import android.content.Context
import it.frumorn.tratto.data.Archivio

/** Applicazione: tiene l'archivio delle note, creato al primo uso e condiviso da tutte le schermate. */
class TrattoApp : Application() {
    val archivio: Archivio by lazy { Archivio(this) }

    companion object {
        fun archivio(context: Context): Archivio = (context.applicationContext as TrattoApp).archivio
    }
}
