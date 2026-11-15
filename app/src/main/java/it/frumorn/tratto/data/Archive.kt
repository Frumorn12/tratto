package it.frumorn.tratto.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.util.UUID

/**
 * Archivio delle note su disco.
 *
 *   files/tratto/indice.json              elenco di note e cartelle (basta per la libreria)
 *   files/tratto/note/<id>/nota.json      pagine della nota
 *   files/tratto/note/<id>/pagine/<p>.tp  tratti di ogni pagina (FormatoPagina)
 *   files/tratto/note/<id>/pagine/<p>.oggetti.json
 *                                         immagini e caselle di testo della pagina (FormatoOggetti), se ce ne sono
 *   files/tratto/note/<id>/immagini/<i>.webp  immagini incollate o inserite, al massimo 2048 px di lato
 *   files/tratto/note/<id>/allegato.pdf   PDF importato, se c'e'
 *   files/tratto/note/<id>/anteprima.webp copertina per la libreria
 *
 * Tutti i metodi fanno I/O: vanno chiamati fuori dal thread principale.
 */
class Archive(
    /** Cartella dell'archivio (files/tratto). */
    val radice: File,
    /** Cartella per i file temporanei (la cacheDir), usata dal ripristino dei backup. */
    val temporanei: File,
) {
    constructor(context: Context) : this(File(context.filesDir, "tratto"), context.cacheDir)

    private val cartellaNote = File(radice, "note")
    private val fileIndice = File(radice, "indice.json")

    private val _note = MutableStateFlow<List<NotaInfo>>(emptyList())
    val note: StateFlow<List<NotaInfo>> = _note.asStateFlow()
    private val _cartelle = MutableStateFlow<List<Cartella>>(emptyList())
    val cartelle: StateFlow<List<Cartella>> = _cartelle.asStateFlow()

    @Volatile private var caricato = false

    @Synchronized
    fun caricaIndice() {
        if (caricato) return
        caricato = true
        recuperaScambioInterrotto()
        if (!fileIndice.exists()) return
        val (note, cartelle) = leggiIndice(fileIndice.readText())
        _cartelle.value = cartelle
        _note.value = note.sortedByDescending { it.modificata }
    }

    fun cartellaNota(id: String) = File(cartellaNote, id)
    fun fileAnteprima(id: String) = File(cartellaNota(id), "anteprima.webp")
    fun filePdf(id: String) = File(cartellaNota(id), "allegato.pdf")
    private fun filePagina(notaId: String, paginaId: String) = File(cartellaNota(notaId), "pagine/$paginaId.tp")
    private fun fileOggetti(notaId: String, paginaId: String) = File(cartellaNota(notaId), "pagine/$paginaId$OGGETTI")

    /** File di un'immagine della nota; [file] e' il percorso relativo salvato nell'oggetto. */
    fun fileImmagine(notaId: String, file: String) = File(cartellaNota(notaId), file)

    fun nuovaNota(sfondo: Sfondo, cartellaId: String?, capitoloId: String? = null, titolo: String = ""): NotaInfo {
        val ora = System.currentTimeMillis()
        val info = NotaInfo(
            id = nuovoId(), titolo = titolo, cartellaId = cartellaId, creata = ora, modificata = ora,
            preferita = false, pagine = 1, sfondo = sfondo, conPdf = false,
            capitoloId = capitoloId.takeIf { cartellaId != null },
        )
        salvaPagine(info.id, listOf(Pagina(nuovoId(), sfondo)))
        _note.update { listOf(info) + it }
        salvaIndice()
        return info
    }

    /**
     * Mette nell'indice una nota preparata altrove, con il suo id: la nota rapida esiste solo in
     * memoria finche' non si scrive il primo tratto. Le pagine le salva poi il suo Documento.
     */
    fun aggiungiNota(info: NotaInfo) {
        _note.update { l -> listOf(info) + l.filter { it.id != info.id } }
        salvaIndice()
    }

    /** Crea una nota a partire da un PDF: una pagina di Tratto per ogni pagina del PDF. */
    fun notaDaPdf(titolo: String, cartellaId: String?, copiaPdf: (File) -> Unit, pagine: List<Pair<Float, Float>>): NotaInfo {
        val ora = System.currentTimeMillis()
        val id = nuovoId()
        copiaPdf(filePdf(id).also { it.parentFile?.mkdirs() })
        val elenco = pagine.mapIndexed { i, (w, h) ->
            // Larghezza fissa a 1000 unita', altezza in proporzione alla pagina del PDF.
            Pagina(nuovoId(), Sfondo.BIANCO, pdfPagina = i, larghezza = Foglio.LARGHEZZA, altezza = Foglio.LARGHEZZA * h / w)
        }
        salvaPagine(id, elenco)
        val info = NotaInfo(id, titolo, cartellaId, ora, ora, false, elenco.size, Sfondo.BIANCO, conPdf = true)
        _note.update { listOf(info) + it }
        salvaIndice()
        return info
    }

    /**
     * Nota già pronta (import). Resta alla radice, con le date d'origine: niente cartella,
     * e [tocca] non deve riscrivere la data di modifica.
     */
    fun notaComposta(
        titolo: String,
        creata: Long,
        modificata: Long,
        pagine: List<Pagina>,
        tratti: Map<String, List<Tratto>>,
        oggetti: Map<String, List<Oggetto>>,
    ): NotaInfo {
        val id = nuovoId()
        val elenco = pagine.ifEmpty { listOf(Pagina(nuovoId(), Sfondo.BIANCO)) }
        scriviAtomico(File(cartellaNota(id), "nota.json"), scriviPagine(elenco).toByteArray())
        for (p in elenco) {
            tratti[p.id]?.takeIf { it.isNotEmpty() }?.let { PageFormat.scrivi(filePagina(id, p.id), it) }
            oggetti[p.id]?.takeIf { it.isNotEmpty() }?.let { scriviAtomico(fileOggetti(id, p.id), ObjectFormat.scrivi(it).toByteArray()) }
        }
        val info = NotaInfo(id, titolo, null, creata, modificata, false, elenco.size, elenco.first().sfondo, false)
        _note.update { listOf(info) + it }
        salvaIndice()
        return info
    }

    fun pagine(notaId: String): List<Pagina> {
        val f = File(cartellaNota(notaId), "nota.json")
        if (!f.exists()) return emptyList()
        return leggiPagine(f.readText())
    }

    fun salvaPagine(notaId: String, pagine: List<Pagina>) {
        scriviAtomico(File(cartellaNota(notaId), "nota.json"), scriviPagine(pagine).toByteArray())
        aggiorna(notaId) { it.copy(pagine = pagine.size) }
    }

    /**
     * Pages Noteshelf trop grandes (1440 / 2304) ou paysage mal cadré : même largeur Tratto (1000),
     * traits et objets redimensionnés. Renvoie le nombre de notes touchées.
     */
    fun normalizzaPagineNoteshelf(): Int {
        var n = 0
        for (info in note.value) {
            val elenco = pagine(info.id).toMutableList()
            var cambiata = false
            for (i in elenco.indices) {
                val idPagina = elenco[i].id
                val tratti = tratti(info.id, idPagina)
                val oggetti = oggetti(info.id, idPagina)
                val norm = normalizzaPaginaAFoglio(elenco[i], tratti, oggetti) ?: continue
                elenco[i] = norm.pagina
                salvaTratti(info.id, idPagina, norm.tratti)
                if (norm.oggetti.isNotEmpty() || oggetti.isNotEmpty()) salvaOggetti(info.id, idPagina, norm.oggetti)
                cambiata = true
            }
            if (!cambiata) continue
            salvaPagine(info.id, elenco)
            fileAnteprima(info.id).delete()
            n++
        }
        return n
    }

    fun tratti(notaId: String, paginaId: String): MutableList<Tratto> = PageFormat.leggi(filePagina(notaId, paginaId))

    fun salvaTratti(notaId: String, paginaId: String, tratti: List<Tratto>) {
        PageFormat.scrivi(filePagina(notaId, paginaId), tratti)
        tocca(notaId)
    }

    fun eliminaPagina(notaId: String, paginaId: String) {
        filePagina(notaId, paginaId).delete()
        fileOggetti(notaId, paginaId).delete()
    }

    /** Cancella i file delle pagine che non fanno piu' parte della nota. */
    fun pagineOrfane(notaId: String, vive: Set<String>) {
        File(cartellaNota(notaId), "pagine").listFiles()?.forEach { f ->
            if (f.name.endsWith(".tp") && f.nameWithoutExtension !in vive) f.delete()
            if (f.name.endsWith(OGGETTI) && f.name.removeSuffix(OGGETTI) !in vive) f.delete()
        }
    }

    /**
     * Immagini e caselle di testo di una pagina. Le note di prima degli oggetti non hanno il file:
     * la pagina ha solo tratti. Un file illeggibile si tratta come vuoto (e resta li' finche' la pagina
     * non cambia), per non bloccare l'apertura della nota.
     */
    fun oggetti(notaId: String, paginaId: String): MutableList<Oggetto> {
        val f = fileOggetti(notaId, paginaId)
        if (!f.exists()) return mutableListOf()
        return try {
            ObjectFormat.leggi(f.readText())
        } catch (e: IOException) {
            android.util.Log.w("Archivio", "Oggetti della pagina $paginaId illeggibili", e)
            mutableListOf()
        }
    }

    /** Salva gli oggetti di una pagina; senza oggetti il file si toglie, cosi' la pagina resta come prima. */
    fun salvaOggetti(notaId: String, paginaId: String, oggetti: List<Oggetto>) {
        val f = fileOggetti(notaId, paginaId)
        if (oggetti.isEmpty()) f.delete() else scriviAtomico(f, ObjectFormat.scrivi(oggetti).toByteArray())
        tocca(notaId)
    }

    /**
     * Scrive una nuova immagine nella nota con [scrivi] e restituisce il percorso da mettere
     * nell'oggetto. Le immagini non si modificano mai: una copia o un ridimensionamento usano lo stesso file.
     */
    fun salvaImmagine(notaId: String, estensione: String = "webp", scrivi: (java.io.OutputStream) -> Unit): String {
        val relativo = "immagini/${nuovoId()}.$estensione"
        val f = File(cartellaNota(notaId), relativo)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { out ->
            scrivi(out)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) { tmp.delete(); throw IOException("Impossible d'enregistrer l'image") }
        return relativo
    }

    /**
     * Cancella le immagini che nessuna pagina usa piu'. Si chiama alla chiusura della nota: finche' e'
     * aperta, "annulla" puo' riportare in vita un'immagine tolta.
     */
    fun immaginiOrfane(notaId: String) {
        val presenti = File(cartellaNota(notaId), "immagini").listFiles() ?: return
        val usate = HashSet<String>()
        File(cartellaNota(notaId), "pagine").listFiles()?.forEach { f ->
            if (f.name.endsWith(OGGETTI)) {
                oggetti(notaId, f.name.removeSuffix(OGGETTI)).forEach { o -> if (o is Immagine) usate += o.file }
            }
        }
        for (f in presenti) if ("immagini/${f.name}" !in usate) f.delete()
    }

    /**
     * Il testo di tutte le caselle di testo della nota, pagina per pagina e nell'ordine in cui sono
     * disegnate, separate da una riga vuota. Serve a chi indicizza o copia altrove il contenuto delle note.
     */
    fun testoNota(notaId: String): String =
        pagine(notaId).flatMap { p -> oggetti(notaId, p.id).filterIsInstance<Testo>() }
            .map { it.contenuto.testoSemplice.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    fun rinomina(id: String, titolo: String) = aggiorna(id) { it.copy(titolo = titolo) }
    fun preferita(id: String, si: Boolean) = aggiorna(id) { it.copy(preferita = si) }
    /** Cambiando quaderno la nota esce dal suo capitolo; nello stesso quaderno lo tiene. */
    fun sposta(id: String, cartellaId: String?) = aggiorna(id) {
        if (it.cartellaId == cartellaId) it else it.copy(cartellaId = cartellaId, capitoloId = null)
    }

    /** Porta la nota nel capitolo [capitoloId] del quaderno [cartellaId] (null: nel quaderno, fuori dai capitoli). */
    fun spostaInCapitolo(id: String, cartellaId: String, capitoloId: String?) =
        aggiorna(id) { it.copy(cartellaId = cartellaId, capitoloId = capitoloId) }

    /** Segna la nota come modificata e la porta in cima alla libreria. */
    fun tocca(id: String) {
        val ora = System.currentTimeMillis()
        _note.update { lista ->
            val n = lista.find { it.id == id } ?: return@update lista
            listOf(n.copy(modificata = ora)) + lista.filter { it.id != id }
        }
        salvaIndice()
    }

    fun elimina(ids: Set<String>) {
        _note.update { l -> l.filter { it.id !in ids } }
        salvaIndice()
        ids.forEach { cartellaNota(it).deleteRecursively() }
    }

    /**
     * Unisce almeno due note in una sola: resta la piu' vecchia (per data di creazione), le pagine
     * delle altre si appendono in ordine di creazione (tratti, oggetti e immagini). Le note fuse
     * spariscono. Lo sfondo PDF delle pagine aggiunte non si porta dietro (resta solo quello della
     * nota di destinazione, se ce l'ha).
     */
    fun unisciNote(ids: Collection<String>): NotaInfo {
        val scelte = ids.mapNotNull { id -> _note.value.find { it.id == id } }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.creata }, { it.id }))
        require(scelte.size >= 2) { "Serve almeno due note da unire" }
        val destinazione = scelte.first()
        val destId = destinazione.id
        val elenco = ArrayList(pagine(destId))
        for (src in scelte.drop(1)) {
            for (p in pagine(src.id)) {
                val nuovaId = nuovoId()
                val trattiSrc = tratti(src.id, p.id)
                val oggettiNuovi = oggetti(src.id, p.id).map { copiaOggettoVerso(destId, src.id, it) }
                elenco += p.copy(id = nuovaId, pdfPagina = -1)
                if (trattiSrc.isNotEmpty()) PageFormat.scrivi(filePagina(destId, nuovaId), trattiSrc)
                if (oggettiNuovi.isNotEmpty()) {
                    scriviAtomico(fileOggetti(destId, nuovaId), ObjectFormat.scrivi(oggettiNuovi).toByteArray())
                }
            }
        }
        salvaPagine(destId, elenco)
        fileAnteprima(destId).delete()
        elimina(scelte.drop(1).map { it.id }.toSet())
        tocca(destId)
        return _note.value.first { it.id == destId }
    }

    /** Copia un oggetto (e il file immagine, se serve) dalla nota [da] verso [verso]. */
    private fun copiaOggettoVerso(verso: String, da: String, o: Oggetto): Oggetto = when (o) {
        is Immagine -> {
            val src = fileImmagine(da, o.file)
            val file = if (src.isFile) {
                val estensione = src.extension.ifBlank { "webp" }
                salvaImmagine(verso, estensione) { out -> src.inputStream().use { it.copyTo(out) } }
            } else {
                o.file
            }
            o.copy(id = nuovoId(), file = file)
        }
        is Testo -> o.copy(id = nuovoId())
    }

    fun nuovaCartella(nome: String, colore: Int): Cartella {
        val c = Cartella(nuovoId(), nome, colore)
        _cartelle.update { it + c }
        salvaIndice()
        return c
    }

    fun modificaCartella(id: String, nome: String, colore: Int) {
        _cartelle.update { l -> l.map { if (it.id == id) it.copy(nome = nome, colore = colore) else it } }
        salvaIndice()
    }

    fun eliminaCartella(id: String) {
        _cartelle.update { l -> l.filter { it.id != id } }
        _note.update { l -> l.map { if (it.cartellaId == id) it.copy(cartellaId = null, capitoloId = null) else it } }
        salvaIndice()
    }

    fun nuovoCapitolo(cartellaId: String, nome: String, colore: Int? = null): Capitolo {
        val c = Capitolo(nuovoId(), nome, colore)
        _cartelle.update { l -> l.map { if (it.id == cartellaId) it.copy(capitoli = it.capitoli + c) else it } }
        salvaIndice()
        return c
    }

    fun modificaCapitolo(cartellaId: String, id: String, nome: String, colore: Int?) {
        _cartelle.update { l ->
            l.map { q ->
                if (q.id != cartellaId) q
                else q.copy(capitoli = q.capitoli.map { if (it.id == id) it.copy(nome = nome, colore = colore) else it })
            }
        }
        salvaIndice()
    }

    /** Le note del capitolo restano nel quaderno, fuori dai capitoli. */
    fun eliminaCapitolo(cartellaId: String, id: String) {
        _cartelle.update { l -> l.map { q -> if (q.id == cartellaId) q.copy(capitoli = q.capitoli.filter { it.id != id }) else q } }
        _note.update { l -> l.map { if (it.cartellaId == cartellaId && it.capitoloId == id) it.copy(capitoloId = null) else it } }
        salvaIndice()
    }

    /** Ricarica tutto da disco (dopo un ripristino del backup). */
    @Synchronized
    fun ricarica() {
        caricato = false
        _note.value = emptyList()
        _cartelle.value = emptyList()
        caricaIndice()
    }

    /**
     * Il ripristino di un backup sostituisce l'archivio con due rename (vedi BackupLocale). Se l'app muore
     * proprio tra i due, "tratto" manca ma "tratto.nuovo" e' completo: lo si rimette al suo posto.
     */
    private fun recuperaScambioInterrotto() {
        if (radice.exists()) return
        val nuova = File(radice.path + ".nuovo")
        if (nuova.isDirectory) nuova.renameTo(radice)
    }

    private fun aggiorna(id: String, f: (NotaInfo) -> NotaInfo) {
        _note.update { l -> l.map { if (it.id == id) f(it) else it } }
        salvaIndice()
    }

    @Synchronized
    private fun salvaIndice() {
        scriviAtomico(fileIndice, scriviIndice(_note.value, _cartelle.value).toByteArray())
    }

    companion object {
        /** Suffisso dei file degli oggetti, accanto al .tp della pagina. */
        const val OGGETTI = ".oggetti.json"

        fun nuovoId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        fun scriviIndice(note: List<NotaInfo>, cartelle: List<Cartella>): String {
            val sw = StringWriter()
            JsonWriter(sw).use { w ->
                w.beginObject()
                w.name("versione").value(1)
                w.name("cartelle").beginArray()
                for (c in cartelle) {
                    w.beginObject().name("id").value(c.id).name("nome").value(c.nome).name("colore").value(c.colore.toLong())
                    if (c.capitoli.isNotEmpty()) {
                        w.name("capitoli").beginArray()
                        for (k in c.capitoli) {
                            w.beginObject().name("id").value(k.id).name("nome").value(k.nome)
                            k.colore?.let { w.name("colore").value(it.toLong()) }
                            w.endObject()
                        }
                        w.endArray()
                    }
                    w.endObject()
                }
                w.endArray()
                w.name("note").beginArray()
                for (n in note) {
                    w.beginObject()
                    w.name("id").value(n.id)
                    w.name("titolo").value(n.titolo)
                    if (n.cartellaId != null) w.name("cartella").value(n.cartellaId)
                    if (n.cartellaId != null && n.capitoloId != null) w.name("capitolo").value(n.capitoloId)
                    w.name("creata").value(n.creata)
                    w.name("modificata").value(n.modificata)
                    w.name("preferita").value(n.preferita)
                    w.name("pagine").value(n.pagine.toLong())
                    w.name("sfondo").value(n.sfondo.name)
                    w.name("pdf").value(n.conPdf)
                    w.endObject()
                }
                w.endArray()
                w.endObject()
            }
            return sw.toString()
        }

        fun leggiIndice(testo: String): Pair<List<NotaInfo>, List<Cartella>> {
            val note = ArrayList<NotaInfo>()
            val cartelle = ArrayList<Cartella>()
            JsonReader(StringReader(testo)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "cartelle" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                var id = ""; var nome = ""; var colore = 0
                                val capitoli = ArrayList<Capitolo>()
                                r.beginObject()
                                while (r.hasNext()) when (r.nextName()) {
                                    "id" -> id = r.nextString()
                                    "nome" -> nome = r.nextString()
                                    "colore" -> colore = r.nextLong().toInt()
                                    "capitoli" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            var kid = ""; var knome = ""; var kcolore: Int? = null
                                            r.beginObject()
                                            while (r.hasNext()) when (r.nextName()) {
                                                "id" -> kid = r.nextString()
                                                "nome" -> knome = r.nextString()
                                                "colore" -> kcolore = r.nextLong().toInt()
                                                else -> r.skipValue()
                                            }
                                            r.endObject()
                                            if (kid.isNotEmpty() && capitoli.none { it.id == kid }) capitoli += Capitolo(kid, knome, kcolore)
                                        }
                                        r.endArray()
                                    }
                                    else -> r.skipValue()
                                }
                                r.endObject()
                                cartelle += Cartella(id, nome, colore, capitoli)
                            }
                            r.endArray()
                        }
                        "note" -> {
                            r.beginArray()
                            while (r.hasNext()) note += leggiNotaInfo(r)
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            return note to cartelle
        }

        private fun leggiNotaInfo(r: JsonReader): NotaInfo {
            var id = ""; var titolo = ""; var cartella: String? = null; var capitolo: String? = null
            var creata = 0L; var modificata = 0L; var preferita = false; var pagine = 1
            var sfondo = Sfondo.BIANCO; var pdf = false
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "id" -> id = r.nextString()
                "titolo" -> titolo = r.nextString()
                "cartella" -> if (r.peek() == JsonToken.NULL) r.nextNull() else cartella = r.nextString()
                "capitolo" -> if (r.peek() == JsonToken.NULL) r.nextNull() else capitolo = r.nextString()
                "creata" -> creata = r.nextLong()
                "modificata" -> modificata = r.nextLong()
                "preferita" -> preferita = r.nextBoolean()
                "pagine" -> pagine = r.nextInt()
                "sfondo" -> sfondo = runCatching { Sfondo.valueOf(r.nextString()) }.getOrDefault(Sfondo.BIANCO)
                "pdf" -> pdf = r.nextBoolean()
                else -> r.skipValue()
            }
            r.endObject()
            return NotaInfo(id, titolo, cartella, creata, modificata, preferita, pagine, sfondo, pdf, capitolo.takeIf { cartella != null })
        }

        fun scriviPagine(pagine: List<Pagina>): String {
            val sw = StringWriter()
            JsonWriter(sw).use { w ->
                w.beginObject().name("versione").value(1).name("pagine").beginArray()
                for (p in pagine) {
                    w.beginObject()
                    w.name("id").value(p.id)
                    w.name("sfondo").value(p.sfondo.name)
                    if (p.pdfPagina >= 0) w.name("pdf").value(p.pdfPagina.toLong())
                    w.name("w").value(p.larghezza.toDouble())
                    w.name("h").value(p.altezza.toDouble())
                    w.endObject()
                }
                w.endArray().endObject()
            }
            return sw.toString()
        }

        fun leggiPagine(testo: String): List<Pagina> {
            val out = ArrayList<Pagina>()
            JsonReader(StringReader(testo)).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "pagine") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var id = ""; var sfondo = Sfondo.BIANCO; var pdf = -1
                        var w = Foglio.LARGHEZZA; var h = Foglio.ALTEZZA
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "id" -> id = r.nextString()
                            "sfondo" -> sfondo = runCatching { Sfondo.valueOf(r.nextString()) }.getOrDefault(Sfondo.BIANCO)
                            "pdf" -> pdf = r.nextInt()
                            "w" -> w = r.nextDouble().toFloat()
                            "h" -> h = r.nextDouble().toFloat()
                            else -> r.skipValue()
                        }
                        r.endObject()
                        out += Pagina(id, sfondo, pdf, w, h)
                    }
                    r.endArray()
                }
                r.endObject()
            }
            return out
        }
    }
}
