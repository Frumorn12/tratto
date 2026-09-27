package it.frumorn.tratto

import android.os.Bundle
import androidx.activity.ComponentActivity
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.editor.Documento
import it.frumorn.tratto.editor.EditorView

/** Schermata provvisoria per provare l'editor sul tablet. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val archivio = Archivio(this)
        archivio.caricaIndice()
        val info = archivio.note.value.firstOrNull() ?: archivio.nuovaNota(Sfondo.RIGHE, null, "Prova")
        val doc = Documento(archivio, info)
        doc.apri()
        doc.pagine.forEach { doc.caricaTratti(it) }
        val v = EditorView(this)
        v.documento = doc
        v.alTratto = { Thread { doc.salva() }.start() }
        setContentView(v)
    }
}
