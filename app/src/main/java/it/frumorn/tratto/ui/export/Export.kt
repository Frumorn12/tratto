package it.frumorn.tratto.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import it.frumorn.tratto.R
import it.frumorn.tratto.data.Archive
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.pdf.PdfExport
import it.frumorn.tratto.ui.testoOra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Esportazione e condivisione di note come PDF o immagine. */
object Export {
    private fun cartella(context: Context) = File(context.cacheDir, "condivisi").apply { mkdirs() }

    private fun nomeFile(info: NotaInfo, estensione: String) =
        PdfExport.titolo(info).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60) + ".$estensione"

    /** Crea il PDF in un file temporaneo e apre il foglio di condivisione di Android. */
    suspend fun condividiPdf(context: Context, archivio: Archive, info: NotaInfo) {
        val f = withContext(Dispatchers.IO) {
            File(cartella(context), nomeFile(info, "pdf")).also { f -> f.outputStream().use { PdfExport.esporta(context, archivio, info, it) } }
        }
        condividi(context, f, "application/pdf", PdfExport.titolo(info))
    }

    suspend fun salvaPdf(context: Context, archivio: Archive, info: NotaInfo, destinazione: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destinazione, "wt")!!.use { PdfExport.esporta(context, archivio, info, it) }
        }
        Toast.makeText(context, testoOra(R.string.pdf_saved), Toast.LENGTH_SHORT).show()
    }

    suspend fun condividiPagina(context: Context, archivio: Archive, info: NotaInfo, pagina: Pagina, numero: Int) {
        val f = withContext(Dispatchers.IO) {
            File(cartella(context), nomeFile(info, "png").removeSuffix(".png") + " - pagina $numero.png").also { f ->
                f.outputStream().use { PdfExport.esportaPagina(archivio, info, pagina, 1600, it) }
            }
        }
        condividi(context, f, "image/png", PdfExport.titolo(info))
    }

    fun nomePdf(info: NotaInfo) = nomeFile(info, "pdf")

    private fun condividi(context: Context, f: File, tipo: String, titolo: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.file", f)
        val invio = Intent(Intent.ACTION_SEND).setType(tipo).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, titolo)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(invio, testoOra(R.string.share_title, titolo)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
