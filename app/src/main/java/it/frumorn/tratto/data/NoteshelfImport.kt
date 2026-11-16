package it.frumorn.tratto.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.min

/**
 * Un export Noteshelf (`.noteshelf`, un zip) devient une note Tratto par page écrite, à la racine.
 * L'encre est dans une base SQLite par page (`stroke_segments_v3` : x, y, largeur, opacite).
 * La page est ramenée à la largeur Tratto (1000) pour tenir entière à l'écran. Les traits droits
 * et le texte posé sur la page sont repris aussi. Le motif du ruban, les pages vides et la
 * couverture sans encre ne le sont pas.
 *
 * Noteshelf garde souvent un [pdfKitPageRect] portrait même en paysage : [rotationAngle] vaut
 * alors 90 ou 270, et les coordonnées d'encre sont déjà dans le repère paysage (x le long du
 * grand côté). Sans permuter largeur/hauteur, le trait dépasse à droite.
 */
object NoteshelfImport {
    fun importa(archivio: Archive, zip: InputStream, temporanei: File): List<NotaInfo> {
        val dest = File(temporanei, "noteshelf-${System.nanoTime()}").apply { mkdirs() }
        try {
            estrai(zip, dest)
            val documenti = dest.walkTopDown().filter { it.isFile && it.name == "Document.plist" }.toList()
            if (documenti.isEmpty()) {
                val nomi = dest.walkTopDown().take(20).joinToString { it.relativeTo(dest).path }
                throw IOException("noteshelf: $nomi")
            }
            return documenti.flatMap { importaDocumento(archivio, it) }
        } finally {
            dest.deleteRecursively()
        }
    }

    private class PaginaLetta(val pagina: Pagina, val tratti: List<Tratto>, val oggetti: List<Oggetto>, val creata: Long?)

    private fun importaDocumento(archivio: Archive, plist: File): List<NotaInfo> {
        val radice = leggiPlist(plist) as? Map<*, *> ?: throw IOException("noteshelf")
        val cancellate = (radice["deletedPages"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        val pagineNs = (radice["pages"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val base = plist.parentFile ?: throw IOException("noteshelf")
        val titolo = titoloDi(base)
        var creata = Long.MAX_VALUE
        var modificata = 0L
        leggiPlist(File(base, "Metadata/Properties.plist"))?.let { (it as? Map<*, *>) }?.let { p ->
            numero(p["document_Crt_Dt"])?.let { creata = epochMs(it) }
            numero(p["document_Lst_Mod_Dt"])?.let { modificata = epochMs(it) }
        }
        val lette = ArrayList<PaginaLetta>()
        for (ns in pagineNs) {
            val uuid = ns["uuid"]?.toString() ?: continue
            if (uuid in cancellate) continue
            val db = File(base, "Annotations/$uuid")
            if (!db.isFile || db.length() == 0L) continue
            val (w0, h0) = rettangolo(ns["pdfKitPageRect"]?.toString())
            val (w, h) = dimensioniPagina(w0, h0, numero(ns["rotationAngle"]) ?: 0.0)
            // Largeur Tratto = 1000 : l'éditeur coupe tout ce qui dépasse. On réduit la page Noteshelf
            // proportionnellement (l'encre garde sa forme ; la largeur variable des segments aussi).
            val scala = Foglio.LARGHEZZA / w
            val (trattiPagina, oggettiPagina) = leggiPagina(db, scala)
            if (trattiPagina.isEmpty() && oggettiPagina.isEmpty()) continue
            numero(ns["lastUpdated"])?.let { modificata = maxOf(modificata, epochMs(it)) }
            val pagina = Pagina(Archive.nuovoId(), Sfondo.BIANCO, larghezza = Foglio.LARGHEZZA, altezza = Foglio.LARGHEZZA * h / w)
            lette += PaginaLetta(pagina, trattiPagina, oggettiPagina, numero(ns["creationDate"])?.let { epochMs(it) })
        }
        if (lette.isEmpty()) throw IOException("noteshelf")
        val ora = System.currentTimeMillis()
        if (creata == Long.MAX_VALUE) creata = lette.mapNotNull { it.creata }.minOrNull() ?: ora
        if (modificata == 0L) modificata = creata
        // La bibliothèque trie par date de modification décroissante : une milliseconde d'écart
        // par page garde la page 1 en tête. On insère de la dernière à la première pour la même raison.
        val note = lette.indices.reversed().map { i ->
            val l = lette[i]
            val titoloPagina = if (lette.size == 1) titolo else "$titolo · ${i + 1}"
            archivio.notaComposta(
                titoloPagina, l.creata ?: creata, modificata - i, listOf(l.pagina),
                mapOf(l.pagina.id to l.tratti), mapOf(l.pagina.id to l.oggetti),
            )
        }
        return note.reversed()
    }

    private fun titoloDi(base: File): String {
        leggiPlist(File(base, "Metadata/Properties.plist"))?.let { (it as? Map<*, *>)?.get("relative_path")?.toString() }?.let { percorso ->
            val nome = percorso.trim('/').substringAfterLast('/')
            val pulito = nome.removeSuffix(".ns3_a").removeSuffix(".ns3").trim()
            if (pulito.isNotEmpty()) return pulito
        }
        return base.name.removeSuffix(".ns3_a").removeSuffix(".ns3").ifBlank { "Noteshelf" }
    }

    private fun leggiPagina(dbFile: File, scala: Float): Pair<List<Tratto>, List<Oggetto>> {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val tratti = ArrayList<Tratto>()
        val oggetti = ArrayList<Oggetto>()
        var id = 1L
        try {
            db.rawQuery(
                """SELECT annotationType, strokeWidth, strokeColor, boundingRect_x, boundingRect_y, boundingRect_w, boundingRect_h,
                   stroke_segments_v3, shape_data, nonAttrText, textHTMLForm
                   FROM annotation ORDER BY createdTime""",
                null,
            ).use { c ->
                val tipo = c.getColumnIndexOrThrow("annotationType")
                val larghezza = c.getColumnIndexOrThrow("strokeWidth")
                val colore = c.getColumnIndexOrThrow("strokeColor")
                val x = c.getColumnIndexOrThrow("boundingRect_x")
                val y = c.getColumnIndexOrThrow("boundingRect_y")
                val w = c.getColumnIndexOrThrow("boundingRect_w")
                val h = c.getColumnIndexOrThrow("boundingRect_h")
                val segmenti = c.getColumnIndexOrThrow("stroke_segments_v3")
                val forma = c.getColumnIndexOrThrow("shape_data")
                val testo = c.getColumnIndexOrThrow("nonAttrText")
                val html = c.getColumnIndexOrThrow("textHTMLForm")
                while (c.moveToNext()) {
                    when (c.getInt(tipo)) {
                        0 -> {
                            val blob = c.getBlob(segmenti) ?: continue
                            // strokeWidth (souvent 2) est le réglage du stylo ; l'épaisseur réelle est dans chaque segment.
                            val secours = c.getDouble(larghezza).toFloat() * scala
                            trattoDaSegmenti(blob, scala, coloreArgb(c.getInt(colore)), secours, Penna.PENNA, id)?.let {
                                tratti += it
                                id++
                            }
                        }
                        5 -> {
                            // shape_data est un BLOB : getString lève « Unable to convert BLOB to string ».
                            val json = c.getBlob(forma)?.toString(Charsets.UTF_8) ?: continue
                            val secours = c.getDouble(larghezza).toFloat() * scala
                            trattoDaForma(json, scala, coloreArgb(c.getInt(colore)), secours, id)?.let {
                                tratti += it
                                id++
                            }
                        }
                        9 -> {
                            val blob = c.getBlob(segmenti) ?: continue
                            val banda = min(c.getDouble(w), c.getDouble(h)).toFloat() * scala * 0.55f
                            trattoDaSegmenti(blob, scala, coloreArgb(c.getInt(colore)), banda.coerceIn(8f, 40f), Penna.EVIDENZIATORE, id, epaisseurFissa = true)?.let {
                                tratti += it
                                id++
                            }
                        }
                        1 -> {
                            val piano = testoPiano(c.getString(testo), c.getString(html))
                            if (piano.isBlank()) continue
                            val punti = TAILLE.find(c.getString(html) ?: "")?.groupValues?.get(1)?.toFloatOrNull()
                            oggetti += Testo(
                                Archive.nuovoId(),
                                c.getDouble(x).toFloat() * scala,
                                c.getDouble(y).toFloat() * scala,
                                (c.getDouble(w).toFloat() * scala).coerceAtLeast(Testo.LARGHEZZA_MIN),
                                TestoRicco(listOf(Paragrafo(listOf(Frammento(piano, punti = punti))))),
                            )
                        }
                    }
                }
            }
        } finally {
            db.close()
        }
        return tratti to oggetti
    }

    private fun estrai(zip: InputStream, dest: File) {
        // L'export Noteshelf met les tailles dans un descripteur de données (bit 3).
        // ZipInputStream n'en voit alors aucune entrée ; ZipFile lit le catalogue central.
        val copia = File(dest, "source.noteshelf")
        copia.outputStream().use { zip.copyTo(it) }
        val radice = dest.canonicalFile
        var scritti = 0L
        ZipFile(copia).use { z ->
            val voci = z.entries()
            while (voci.hasMoreElements()) {
                val voce = voci.nextElement()
                if (voce.isDirectory) continue
                val nome = voce.name
                if (nome.contains("..") || nome.startsWith("/") || nome.startsWith("\\")) throw IOException("noteshelf")
                val out = File(dest, nome).canonicalFile
                if (!out.path.startsWith(radice.path + File.separator)) throw IOException("noteshelf")
                out.parentFile?.mkdirs()
                z.getInputStream(voce).use { input -> out.outputStream().use { input.copyTo(it) } }
                scritti += out.length()
                if (scritti > 256L * 1024 * 1024) throw IOException("noteshelf")
            }
        }
        copia.delete()
    }
}

/**
 * Multiplicateur d'épaisseur du stylo à bille à pleine pression (Brushes.PENNA : max 1,45).
 * À pression 1, largeur à l'écran ≈ [spessore] × PENNA_PIENA.
 */
internal const val PENNA_PIENA = 1.45f

/** Intervalle synthétique entre points : les segments Noteshelf n'ont pas d'horloge, seulement une largeur. */
internal const val MS_PER_PUNTO = 8f

/**
 * Segment Noteshelf de 16 octets : x, y (f32), largeur × 100 (u16), opacité × 100 (u16), pad.
 * La largeur monte puis redescend (filage en fin de trait) : ce n'est pas un temps.
 * On la mappe sur la pression du pinceau Tratto ; le temps est synthétique pour qu'Ink lisse juste.
 * [spessore] : secours (colonne strokeWidth) ou largeur fixe du ruban si [epaisseurFissa].
 */
internal fun trattoDaSegmenti(
    blob: ByteArray,
    scala: Float,
    colore: Int,
    spessore: Float,
    penna: Penna,
    id: Long,
    epaisseurFissa: Boolean = false,
): Tratto? {
    val n = blob.size / 16
    if (n < 1) return null
    val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    val larghezze = FloatArray(n)
    val xy = FloatArray(n * 2)
    for (i in 0 until n) {
        xy[i * 2] = b.float * scala
        xy[i * 2 + 1] = b.float * scala
        val r = (b.short.toInt() and 0xFFFF) / 100f
        b.short
        b.int
        larghezze[i] = if (r > 0f) r * scala else 0f
    }
    val spesso: Float
    val punti = FloatArray(n * Tratto.CAMPI)
    if (epaisseurFissa || penna == Penna.EVIDENZIATORE) {
        spesso = spessore.coerceIn(0.5f, 48f)
        for (i in 0 until n) {
            val o = i * Tratto.CAMPI
            punti[o] = xy[i * 2]
            punti[o + 1] = xy[i * 2 + 1]
            punti[o + 2] = 1f
            punti[o + 3] = -1f
            punti[o + 4] = -1f
            punti[o + 5] = i * MS_PER_PUNTO
        }
    } else {
        val maxL = larghezze.maxOrNull()?.takeIf { it > 0f } ?: spessore
        // À pression 1 le pinceau grossit × PENNA_PIENA : on calibre sur la plus grosse largeur du trait.
        spesso = (maxL / PENNA_PIENA).coerceIn(0.5f, 48f)
        val denom = (PENNA_PIENA - 0.34f).coerceAtLeast(0.01f)
        for (i in 0 until n) {
            val o = i * Tratto.CAMPI
            punti[o] = xy[i * 2]
            punti[o + 1] = xy[i * 2 + 1]
            val m = (larghezze[i].takeIf { it > 0f } ?: maxL) / spesso
            // Inverse approx. du SIZE_MULTIPLIER (0,34…1,45) : retrouve une pression 0…1.
            punti[o + 2] = ((m - 0.34f) / denom).coerceIn(0.05f, 1f)
            punti[o + 3] = -1f
            punti[o + 4] = -1f
            punti[o + 5] = i * MS_PER_PUNTO
        }
    }
    return Tratto(id, penna, colore, spesso, punti)
}

/** Trait droit : le JSON de forme contient des points de contrôle en base64. */
internal fun trattoDaForma(json: String, scala: Float, colore: Int, spessore: Float, id: Long): Tratto? {
    val interno = runCatching {
        val dati = JSONObject(json).optString("shapeData")
        if (dati.isEmpty()) null else JSONObject(String(Base64.getDecoder().decode(dati)))
    }.getOrNull() ?: return null
    val puntiNs = interno.optJSONArray("controlPoints") ?: return null
    if (puntiNs.length() < 2) return null
    val epaisseurNs = interno.optJSONObject("properties")?.optDouble("strokeThickness")?.takeIf { it > 0 }?.toFloat()?.times(scala)
    val spesso = ((epaisseurNs ?: spessore) / PENNA_PIENA).coerceIn(0.5f, 48f)
    val punti = FloatArray(puntiNs.length() * Tratto.CAMPI)
    for (i in 0 until puntiNs.length()) {
        val p = puntiNs.optJSONArray(i) ?: return null
        val o = i * Tratto.CAMPI
        punti[o] = p.optDouble(0).toFloat() * scala
        punti[o + 1] = p.optDouble(1).toFloat() * scala
        punti[o + 2] = 1f
        punti[o + 3] = -1f
        punti[o + 4] = -1f
        punti[o + 5] = i * MS_PER_PUNTO * 4
    }
    return Tratto(id, Penna.PENNA, colore, spesso, punti)
}

internal fun coloreArgb(noteshelf: Int): Int = (noteshelf and 0xFFFFFF) or 0xFF000000.toInt()

internal fun leggiPlist(file: File): Any? {
    if (!file.isFile) return null
    return file.inputStream().use { leggiPlist(it) }
}

internal fun leggiPlist(xml: InputStream): Any? {
    // Le DOCTYPE Apple pointe une DTD distante. On le retire : le parseur Android refuse
    // souvent de la charger, et le document n'en a pas besoin.
    val testo = xml.bufferedReader(Charsets.UTF_8).readText().replace(DOCTYPE, "")
    val fabbrica = DocumentBuilderFactory.newInstance()
    runCatching { fabbrica.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { fabbrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { fabbrica.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    fabbrica.isNamespaceAware = false
    val doc = fabbrica.newDocumentBuilder().parse(testo.byteInputStream(Charsets.UTF_8))
    val primo = elementi(doc.documentElement).firstOrNull() ?: return null
    return valorePlist(primo)
}

private fun valorePlist(el: Element): Any? = when (el.tagName) {
    "dict" -> {
        val out = LinkedHashMap<String, Any?>()
        val figli = elementi(el)
        var i = 0
        while (i + 1 < figli.size) {
            val chiave = figli[i]
            val valore = figli[i + 1]
            if (chiave.tagName == "key") out[chiave.textContent.trim()] = valorePlist(valore)
            i += 2
        }
        out
    }
    "array" -> elementi(el).map { valorePlist(it) }
    "string" -> el.textContent
    "integer", "real" -> el.textContent.trim().toDoubleOrNull()
    "true" -> true
    "false" -> false
    else -> null
}

private fun elementi(nodo: org.w3c.dom.Node): List<Element> {
    val out = ArrayList<Element>()
    val figli = nodo.childNodes
    for (i in 0 until figli.length) {
        val f = figli.item(i)
        if (f is Element) out += f
    }
    return out
}

private val DOCTYPE = Regex("<!DOCTYPE[^>]*>", RegexOption.IGNORE_CASE)
private val RECT = Regex("""\{\s*([0-9.]+)\s*,\s*([0-9.]+)""")
private val BALISE = Regex("<[^>]+>")
private val TAILLE = Regex("""font-size:\s*([0-9.]+)\s*pt""")

private fun numero(v: Any?) = (v as? Number)?.toDouble()

private fun epochMs(v: Double) = if (v > 1e11) v.toLong() else (v * 1000).toLong()

private fun rettangolo(testo: String?): Pair<Float, Float> {
    if (testo == null) return 1440f to 2304f
    val n = RECT.findAll(testo).map { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }.toList()
    val dim = n.getOrNull(1) ?: n.firstOrNull() ?: return 1440f to 2304f
    return dim.first.coerceAtLeast(1f) to dim.second.coerceAtLeast(1f)
}

/**
 * [rotationAngle] Noteshelf en degrés. À ~90° / ~270° le rectangle reste portrait mais l'encre
 * est déjà en paysage : on échange largeur et hauteur.
 */
internal fun dimensioniPagina(larghezza: Float, altezza: Float, rotation: Double): Pair<Float, Float> {
    val angle = ((rotation % 360.0) + 360.0) % 360.0
    val paysage = (angle in 45.0..135.0) || (angle in 225.0..315.0)
    return if (paysage) altezza to larghezza else larghezza to altezza
}

/**
 * Pages déjà importées sans tenir compte de [rotationAngle] : l'encre dépasse à droite.
 * On élargit (ou on permute) d'après l'emprise réelle des traits.
 */
internal fun riparaDimensioniPagina(pagina: Pagina, tratti: List<Tratto>): Pagina? {
    if (tratti.isEmpty()) return null
    var maxX = 0f
    var maxY = 0f
    for (t in tratti) {
        var i = 0
        while (i + 1 < t.punti.size) {
            maxX = maxOf(maxX, t.punti[i])
            maxY = maxOf(maxY, t.punti[i + 1])
            i += Tratto.CAMPI
        }
    }
    if (maxX <= pagina.larghezza * 1.02f) return null
    // Encre plus large que la page : cas paysage mal importé (coords déjà tournées).
    val w = maxOf(pagina.altezza, maxX * 1.02f)
    val h = if (maxY <= pagina.larghezza * 1.05f) pagina.larghezza else maxOf(pagina.larghezza, maxY * 1.02f)
    if (abs(w - pagina.larghezza) < 1f && abs(h - pagina.altezza) < 1f) return null
    return pagina.copy(larghezza = w, altezza = h)
}

/**
 * Ramène une page importée à la largeur Tratto ([Foglio.LARGHEZZA] = 1000) en multipliant
 * traits et objets par le même facteur : proportions, placement et épaisseur relative inchangés.
 */
internal data class PaginaNormalizzata(val pagina: Pagina, val tratti: List<Tratto>, val oggetti: List<Oggetto>)

internal fun normalizzaPaginaAFoglio(
    pagina: Pagina,
    tratti: List<Tratto>,
    oggetti: List<Oggetto> = emptyList(),
): PaginaNormalizzata? {
    val riparata = riparaDimensioniPagina(pagina, tratti) ?: pagina
    if (abs(riparata.larghezza - Foglio.LARGHEZZA) < 0.5f) {
        return if (riparata !== pagina && (abs(riparata.larghezza - pagina.larghezza) > 0.5f || abs(riparata.altezza - pagina.altezza) > 0.5f)) {
            PaginaNormalizzata(riparata, tratti, oggetti)
        } else {
            null
        }
    }
    // Ne réduit que les pages trop grandes (Noteshelf natif) ; on ne touche pas aux pages Tratto.
    if (riparata.larghezza < Foglio.LARGHEZZA - 0.5f) return null
    val scala = Foglio.LARGHEZZA / riparata.larghezza
    return PaginaNormalizzata(
        riparata.copy(larghezza = Foglio.LARGHEZZA, altezza = riparata.altezza * scala),
        tratti.map { scalaTratto(it, scala) },
        oggetti.map { scalaOggetto(it, scala) },
    )
}

internal fun scalaTratto(t: Tratto, scala: Float): Tratto {
    if (abs(scala - 1f) < 1e-6f) return t
    val p = t.punti.copyOf()
    var i = 0
    while (i < p.size) {
        p[i] *= scala
        p[i + 1] *= scala
        i += Tratto.CAMPI
    }
    return Tratto(t.id, t.penna, t.colore, t.spessore * scala, p)
}

internal fun scalaOggetto(o: Oggetto, scala: Float): Oggetto = when (o) {
    is Immagine -> o.copy(x = o.x * scala, y = o.y * scala, larghezza = o.larghezza * scala, altezza = o.altezza * scala)
    is Testo -> o.copy(x = o.x * scala, y = o.y * scala, larghezza = (o.larghezza * scala).coerceAtLeast(Testo.LARGHEZZA_MIN))
}

private fun testoPiano(brutto: String?, html: String?): String {
    if (!brutto.isNullOrBlank()) return brutto.trim()
    if (html.isNullOrBlank()) return ""
    return BALISE.replace(html, "").replace("&nbsp;", " ").trim()
}
