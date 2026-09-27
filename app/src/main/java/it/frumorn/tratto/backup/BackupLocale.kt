package it.frumorn.tratto.backup

import android.os.Build
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.scriviAtomico
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Cosa fare delle note gia' presenti quando si ripristina un backup. */
enum class ModoRipristino {
    /** Aggiunge le note che mancano; se una nota c'e' da entrambe le parti vince la piu' recente e l'altra resta come copia. */
    UNISCI,

    /** Butta l'archivio attuale e lo sostituisce con quello del backup. */
    SOSTITUISCI,
}

data class RiepilogoRipristino(
    val aggiunte: Int,
    val aggiornate: Int,
    val copie: Int,
    val cartelleAggiunte: Int = 0,
    /** Note dell'indice del backup senza pagine leggibili: non ripristinate. */
    val saltate: Int = 0,
)

/** Il file non e' un backup di Tratto utilizzabile. Il messaggio e' pronto da mostrare all'utente. */
class BackupNonValido(messaggio: String, causa: Throwable? = null) : IOException(messaggio, causa)

/**
 * Backup locale in un file ".tratto": uno zip con il manifest e l'albero dell'archivio.
 *
 *   manifest.json              {"app":"Tratto","formato":1,"creato":<ms>,"note":<n>,"dispositivo":"<modello>"}
 *   tratto/indice.json         note e cartelle
 *   tratto/note/<id>/...       nota.json, pagine/<p>.tp, pagine/<p>.oggetti.json, immagini/<i>.webp,
 *                              allegato.pdf, anteprima.webp (tutta la cartella della nota, cosi' com'e')
 *
 * Il ripristino estrae e controlla tutto in una cartella temporanea, prepara il nuovo archivio accanto
 * a files/tratto e solo alla fine lo scambia con quello vecchio: se qualcosa va storto a meta',
 * l'archivio resta quello di prima.
 *
 * Tutti i metodi fanno I/O: vanno chiamati fuori dal thread principale.
 */
object BackupLocale {
    const val FORMATO = 1
    const val ESTENSIONE = "tratto"

    /** Tipo da usare con ACTION_CREATE_DOCUMENT: con "application/zip" alcuni provider aggiungerebbero ".zip". */
    const val MIME = "application/octet-stream"

    private const val BUFFER = 64 * 1024
    private const val MAX_VOCI = 200_000
    private const val MARGINE_SPAZIO = 32L shl 20
    private const val CONTROLLO_SPAZIO = 8L shl 20

    /** Gia' compressi: nello zip si salvano senza ricomprimerli (piu' veloce, stesso peso). */
    private val GIA_COMPRESSI = setOf("pdf", "webp", "jpg", "jpeg", "png", "m4a", "mp3", "zip")

    internal const val NON_E_UN_BACKUP = "Il file scelto non è un backup di Tratto."
    internal const val PIU_RECENTE = "Il backup è stato creato da una versione più recente di Tratto: aggiorna l'app per ripristinarlo."
    private const val PERCORSO_NON_VALIDO = "Il backup contiene un percorso non valido"
    private const val DANNEGGIATO = "Il file è danneggiato o non è un backup di Tratto."
    private const val IMPOSSIBILE_SOSTITUIRE = "Impossibile sostituire l'archivio delle note: nessuna modifica fatta."

    /** Nome proposto per il file di backup, es. "Tratto 2026-09-27 15.30.tratto". */
    fun nomeFile(ora: Long = System.currentTimeMillis()): String =
        "Tratto " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.ITALY).format(Date(ora)) + ".$ESTENSIONE"

    /** Scrive in [out] il backup di tutte le note e cartelle. Lo stream non viene chiuso. */
    fun esporta(archivio: Archivio, out: OutputStream) {
        esportaIn(archivio.radice, out, null)
    }

    /** Backup di una sola nota (con la sua voce d'indice e la sua cartella), per condividerla. Lo stream non viene chiuso. */
    fun esportaNota(archivio: Archivio, notaId: String, out: OutputStream) {
        if (esportaIn(archivio.radice, out, notaId).isEmpty()) throw IOException("La nota non esiste più.")
    }

    /**
     * Ripristina un backup letto da [input] (che viene chiuso) e ricarica l'archivio.
     * Lancia [BackupNonValido] se il file non va bene: in quel caso l'archivio non viene toccato.
     */
    fun ripristina(archivio: Archivio, input: InputStream, modo: ModoRipristino): RiepilogoRipristino =
        ripristinaIn(archivio.radice, archivio.temporanei, input, modo, archivio) { archivio.ricarica() }

    // ---------------------------------------------------------------- esportazione

    /** Scrive lo zip e restituisce le voci d'indice delle note esportate (vuota, senza scrivere nulla, se [soloNota] non c'e'). */
    internal fun esportaIn(radice: File, out: OutputStream, soloNota: String?): List<JSONObject> {
        val indice = leggiIndiceDa(File(radice, "indice.json"))
        val note = indice.elenco("note")
            .filter { n ->
                ID_VALIDO.matches(n.id) && (soloNota == null || n.id == soloNota) &&
                    File(radice, "note/${n.id}/nota.json").isFile
            }
            .distinctBy { it.id }
        if (soloNota != null && note.isEmpty()) return note
        val cartelle = if (soloNota == null) {
            indice.elenco("cartelle")
        } else {
            val c = note[0].testo("cartella")
            indice.elenco("cartelle").filter { it.id == c }
        }

        val zip = ZipOutputStream(BufferedOutputStream(out, BUFFER))
        zip.testo("manifest.json", manifest(note.size).toString(2))
        zip.testo("tratto/indice.json", indiceCon(indice, cartelle, note).toString())
        for (n in note) zip.cartella(File(radice, "note/${n.id}"), "tratto/note/${n.id}/")
        if (soloNota == null) {
            // Il resto dell'albero: file che versioni future di Tratto potrebbero mettere accanto all'indice.
            radice.listFiles()?.sortedBy { it.name }?.forEach { f ->
                when {
                    f.name == "indice.json" || f.name == "note" || f.name.endsWith(".tmp") -> Unit
                    f.isDirectory -> zip.cartella(f, "tratto/${f.name}/")
                    else -> zip.file(f, "tratto/${f.name}")
                }
            }
        }
        zip.finish()
        zip.flush()
        return note
    }

    internal fun manifest(note: Int): JSONObject = JSONObject()
        .put("app", "Tratto")
        .put("formato", FORMATO)
        .put("creato", System.currentTimeMillis())
        .put("note", note)
        .put("dispositivo", modello())

    private fun modello(): String = runCatching { Build.MODEL }.getOrNull() ?: "sconosciuto"

    private fun ZipOutputStream.testo(nome: String, testo: String) {
        setLevel(Deflater.DEFAULT_COMPRESSION)
        putNextEntry(ZipEntry(nome))
        write(testo.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.cartella(dir: File, prefisso: String) {
        dir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .sortedBy { it.path }
            .forEach { file(it, prefisso + it.relativeTo(dir).invariantSeparatorsPath) }
    }

    private fun ZipOutputStream.file(f: File, nome: String) {
        // Un file sparito nel frattempo (pagina cancellata mentre si esporta) si salta.
        val input = try { FileInputStream(f) } catch (_: FileNotFoundException) { return }
        input.use {
            setLevel(if (f.extension.lowercase() in GIA_COMPRESSI) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
            putNextEntry(ZipEntry(nome).apply { time = f.lastModified() })
            it.copyTo(this, BUFFER)
            closeEntry()
        }
    }

    // ---------------------------------------------------------------- ripristino

    internal fun ripristinaIn(
        radice: File,
        temporanei: File,
        input: InputStream,
        modo: ModoRipristino,
        blocco: Any,
        ricarica: () -> Unit,
    ): RiepilogoRipristino {
        val lavoro = File(temporanei, "ripristino-${System.nanoTime()}")
        try {
            estrai(input, lavoro)
            val sorgente = leggiSorgente(lavoro)
            return applica(radice, sorgente, modo, blocco, ricarica)
        } finally {
            lavoro.deleteRecursively()
        }
    }

    /**
     * Estrae lo zip in [dest]: solo manifest.json e tratto/..., senza i .tmp.
     * Rifiuta i percorsi che uscirebbero da [dest] (zip-slip).
     */
    internal fun estrai(input: InputStream, dest: File) {
        if (!dest.mkdirs() && !dest.isDirectory) throw IOException("Impossibile creare la cartella temporanea per il ripristino.")
        val base = dest.canonicalFile
        var voci = 0
        var scritti = 0L
        var prossimoControllo = 0L
        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
                var voce: ZipEntry? = zip.nextEntry ?: throw BackupNonValido(NON_E_UN_BACKUP)
                val buf = ByteArray(BUFFER)
                while (voce != null) {
                    val nome = voce.name
                    controllaNome(nome)
                    if (!voce.isDirectory && (nome == "manifest.json" || nome.startsWith("tratto/")) && !nome.endsWith(".tmp")) {
                        if (++voci > MAX_VOCI) throw BackupNonValido("Il backup contiene troppi file.")
                        val f = File(base, nome).canonicalFile
                        if (!f.path.startsWith(base.path + File.separator)) throw BackupNonValido("$PERCORSO_NON_VALIDO: $nome")
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { out ->
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                scritti += n
                                if (scritti >= prossimoControllo) {
                                    if (base.usableSpace < MARGINE_SPAZIO) {
                                        throw IOException("Spazio insufficiente sul dispositivo per ripristinare il backup.")
                                    }
                                    prossimoControllo = scritti + CONTROLLO_SPAZIO
                                }
                            }
                            out.fd.sync()
                        }
                    }
                    voce = zip.nextEntry
                }
            }
        } catch (e: ZipException) {
            // Anche Android (targetSdk 34+) rifiuta da solo i nomi con ".." lanciando ZipException.
            throw BackupNonValido(DANNEGGIATO, e)
        } catch (e: EOFException) {
            throw BackupNonValido("Il file del backup è incompleto o danneggiato.", e)
        }
    }

    private fun controllaNome(nome: String) {
        val nonValido = nome.isEmpty() || nome.startsWith("/") || '\\' in nome || '\u0000' in nome ||
            (nome.length > 1 && nome[1] == ':') || nome.split('/').any { it == ".." }
        if (nonValido) throw BackupNonValido("$PERCORSO_NON_VALIDO: $nome")
    }

    /** Un backup estratto e controllato. [cartella] e' la sua cartella "tratto". */
    internal class Sorgente(
        val cartella: File,
        val indice: JSONObject,
        val note: List<JSONObject>,
        val cartelle: List<JSONObject>,
        val saltate: Int,
    ) {
        fun cartellaNota(id: String) = File(cartella, "note/$id")
    }

    /** Controlla manifest, versioni e indice di un backup estratto in [estratto]. */
    internal fun leggiSorgente(estratto: File): Sorgente {
        val m = leggiJson(File(estratto, "manifest.json")) ?: throw BackupNonValido(NON_E_UN_BACKUP)
        if (m.optString("app") != "Tratto") throw BackupNonValido(NON_E_UN_BACKUP)
        val formato = m.optInt("formato", 0)
        if (formato < 1) throw BackupNonValido("Il backup non indica la versione del formato: il file è danneggiato.")
        if (formato > FORMATO) throw BackupNonValido(PIU_RECENTE)

        val tratto = File(estratto, "tratto")
        val indice = leggiJson(File(tratto, "indice.json"))
            ?: throw BackupNonValido("Il backup è danneggiato: l'indice delle note manca o è illeggibile.")
        if (indice.optInt("versione", 1) > VERSIONE_INDICE) throw BackupNonValido(PIU_RECENTE)

        val visti = HashSet<String>()
        val note = ArrayList<JSONObject>()
        var saltate = 0
        for (n in indice.elenco("note")) {
            val id = n.id
            if (!ID_VALIDO.matches(id) || !visti.add(id)) { saltate++; continue }
            val nota = leggiJson(File(tratto, "note/$id/nota.json"))
            if (nota == null) { saltate++; continue }
            if (nota.optInt("versione", 1) > VERSIONE_NOTA) throw BackupNonValido(PIU_RECENTE)
            note += n
        }
        val cartelle = indice.elenco("cartelle").filter { it.id.isNotEmpty() }.distinctBy { it.id }
        return Sorgente(tratto, indice, note, cartelle, saltate)
    }

    private fun leggiJson(f: File): JSONObject? {
        if (!f.isFile) return null
        return try { JSONObject(f.readText()) } catch (_: JSONException) { null }
    }

    /**
     * Prepara il nuovo archivio in "tratto.preparazione" e poi lo scambia con quello attuale:
     *   tratto.preparazione -> tratto.nuovo,  tratto -> tratto.vecchia,  tratto.nuovo -> tratto.
     * Lo scambio avviene tenendo il lock dell'archivio ([blocco]), cosi' nessun salvataggio dell'indice
     * finisce nella cartella sbagliata. Se l'app muore tra gli ultimi due rename, Archivio rimette a posto
     * "tratto.nuovo" al prossimo avvio.
     */
    internal fun applica(
        radice: File,
        sorgente: Sorgente,
        modo: ModoRipristino,
        blocco: Any,
        ricarica: () -> Unit,
    ): RiepilogoRipristino {
        val preparazione = File(radice.path + ".preparazione")
        val nuova = File(radice.path + ".nuovo")
        val vecchia = File(radice.path + ".vecchia")
        preparazione.deleteRecursively()
        nuova.deleteRecursively()
        vecchia.deleteRecursively()
        try {
            val fileIndice = File(radice, "indice.json")
            val istantanea = if (fileIndice.isFile) fileIndice.readBytes() else null
            val riepilogo = when (modo) {
                ModoRipristino.SOSTITUISCI -> preparaSostituzione(sorgente, preparazione)
                ModoRipristino.UNISCI -> preparaUnione(radice, istantanea, sorgente, preparazione)
            }
            synchronized(blocco) {
                if (modo == ModoRipristino.UNISCI) {
                    // L'unione e' stata calcolata su questa versione dell'indice: se nel frattempo qualcuno
                    // ha salvato una nota, lo scambio perderebbe la modifica.
                    val adesso = if (fileIndice.isFile) fileIndice.readBytes() else null
                    if (!(adesso contentEquals istantanea)) {
                        throw IOException("Le note sono cambiate durante il ripristino: nessuna modifica fatta, riprova.")
                    }
                }
                if (!preparazione.renameTo(nuova)) throw IOException(IMPOSSIBILE_SOSTITUIRE)
                if (radice.exists() && !radice.renameTo(vecchia)) throw IOException(IMPOSSIBILE_SOSTITUIRE)
                if (!nuova.renameTo(radice)) {
                    vecchia.renameTo(radice)
                    throw IOException(IMPOSSIBILE_SOSTITUIRE)
                }
                ricarica()
            }
            vecchia.deleteRecursively()
            return riepilogo
        } finally {
            preparazione.deleteRecursively()
            nuova.deleteRecursively()
        }
    }

    private fun preparaSostituzione(sorgente: Sorgente, dest: File): RiepilogoRipristino {
        val ids = sorgente.note.mapTo(HashSet()) { it.id }
        // Cartelle di note che l'indice non cita: avanzi, non si ripristinano.
        File(sorgente.cartella, "note").listFiles()?.forEach { if (it.name !in ids) it.deleteRecursively() }
        val idCartelle = sorgente.cartelle.mapTo(HashSet()) { it.id }
        val note = sorgente.note.map { n ->
            copiaJson(n).also { c -> if (c.testo("cartella").let { it != null && it !in idCartelle }) c.remove("cartella") }
        }
        scriviAtomico(File(sorgente.cartella, "indice.json"), indiceCon(sorgente.indice, sorgente.cartelle, note).toString().toByteArray())
        sposta(sorgente.cartella, dest)
        return RiepilogoRipristino(
            aggiunte = note.size, aggiornate = 0, copie = 0,
            cartelleAggiunte = sorgente.cartelle.size, saltate = sorgente.saltate,
        )
    }

    private fun preparaUnione(radice: File, istantanea: ByteArray?, sorgente: Sorgente, dest: File): RiepilogoRipristino {
        val locale = try {
            istantanea?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: indiceVuoto()
        } catch (e: JSONException) {
            throw IOException("L'indice delle note sul dispositivo è illeggibile: per ripristinare usa «Sostituisci».", e)
        }
        val piano = pianificaUnione(locale, sorgente, stessoContenuto = { idLocale, idBackup ->
            stessoContenuto(File(radice, "note/$idLocale"), sorgente.cartellaNota(idBackup))
        })
        dest.mkdirs()
        val collega = Collegatore()
        for (op in piano.operazioni) {
            val a = File(dest, "note/${op.destinazione}")
            if (op.daBackup) {
                sposta(sorgente.cartellaNota(op.origine), a)
            } else {
                val da = File(radice, "note/${op.origine}")
                if (da.isDirectory) collega.albero(da, a)
            }
        }
        // Il resto dell'archivio locale (oltre a indice e note) resta com'era.
        radice.listFiles()?.forEach { f ->
            if (f.name != "indice.json" && f.name != "note" && !f.name.endsWith(".tmp")) {
                if (f.isDirectory) collega.albero(f, File(dest, f.name)) else collega.file(f, File(dest, f.name))
            }
        }
        scriviAtomico(File(dest, "indice.json"), piano.indice.toString().toByteArray())
        return piano.riepilogo
    }

    /**
     * Riporta i file dell'archivio attuale nel nuovo con hard link: nessuna copia dei dati, e i file
     * restano validi perche' Archivio non li modifica mai sul posto (scrive un .tmp e poi rinomina).
     * Se il filesystem o SELinux non permettono i link, si copia.
     */
    private class Collegatore {
        private var link = true

        fun albero(da: File, a: File) {
            da.walkTopDown().forEach { f ->
                if (f.isFile && !f.name.endsWith(".tmp")) file(f, File(a, f.relativeTo(da).path))
            }
        }

        fun file(da: File, a: File) {
            a.parentFile?.mkdirs()
            if (link) {
                try {
                    Files.createLink(a.toPath(), da.toPath())
                    return
                } catch (_: NoSuchFileException) {
                    return // sparito nel frattempo: lo scambio se ne accorgera' dall'indice
                } catch (_: Exception) {
                    link = false
                }
            }
            copiaFile(da, a)
        }
    }

    private fun sposta(da: File, a: File) {
        a.parentFile?.mkdirs()
        if (da.renameTo(a)) return
        // Rename impossibile (filesystem diversi): si copia.
        da.walkTopDown().forEach { f -> if (f.isFile) copiaFile(f, File(a, f.relativeTo(da).path)) }
    }

    private fun copiaFile(da: File, a: File) {
        a.parentFile?.mkdirs()
        val input = try { FileInputStream(da) } catch (_: FileNotFoundException) { return }
        input.use { i ->
            FileOutputStream(a).use { o ->
                i.copyTo(o, BUFFER)
                o.fd.sync()
            }
        }
    }

    /** Stesse pagine, stessi oggetti e immagini, stesso PDF (l'anteprima non conta: si rigenera). */
    internal fun stessoContenuto(a: File, b: File): Boolean {
        val fa = contenuto(a) ?: return false
        val fb = contenuto(b) ?: return false
        if (fa.keys != fb.keys) return false
        if (fa.any { (k, f) -> f.length() != fb.getValue(k).length() }) return false
        return fa.all { (k, f) -> stessiByte(f, fb.getValue(k)) }
    }

    private fun contenuto(dir: File): Map<String, File>? {
        if (!dir.isDirectory) return null
        return dir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") && it.name != "anteprima.webp" }
            .associateBy { it.relativeTo(dir).invariantSeparatorsPath }
    }

    private fun stessiByte(a: File, b: File): Boolean {
        FileInputStream(a).use { x ->
            FileInputStream(b).use { y ->
                val ba = ByteArray(BUFFER)
                val bb = ByteArray(BUFFER)
                while (true) {
                    val na = leggiPieno(x, ba)
                    val nb = leggiPieno(y, bb)
                    if (na != nb) return false
                    if (na == 0) return true
                    if (!Arrays.equals(ba, 0, na, bb, 0, nb)) return false
                }
            }
        }
    }

    private fun leggiPieno(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }
}
