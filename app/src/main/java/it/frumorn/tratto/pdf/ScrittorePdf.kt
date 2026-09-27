package it.frumorn.tratto.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import it.frumorn.tratto.data.Foglio
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Sfondo
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** Un tratto pronto per il PDF: lo stile e i contorni in unita' di pagina, [x0, y0, x1, y1, ...] per ognuno. */
class TrattoPdf(val stile: StilePdf, val contorni: List<FloatArray>)

/**
 * Compone il PDF esportato con PdfBox. Non usa API Android (a parte i log di PdfBox), cosi' si prova
 * con i test JVM; Ink e i file della nota restano in [EsportaPdf].
 */
internal object ScrittorePdf {

    /**
     * Scrive in [doc] le [pagine] della nota, nell'ordine della nota.
     *
     * Se [allegato] e' vero, [doc] e' il PDF importato: le pagine con pdfPagina >= 0 riusano la pagina
     * originale (testo selezionabile, link e annotazioni compresi) e i tratti vanno in un content stream
     * aggiunto in coda; le pagine originali che l'utente ha tolto spariscono. Le pagine bianche aggiunte
     * dall'utente sono larghe come la prima pagina del PDF usata dalla nota.
     * Se [allegato] e' falso, [doc] e' vuoto e tutte le pagine sono A4 con il loro sfondo.
     *
     * [tratti] viene chiamato una pagina alla volta, cosi' in memoria c'e' una pagina sola.
     */
    fun componi(
        doc: PDDocument,
        allegato: Boolean,
        pagine: List<Pagina>,
        titolo: String,
        tratti: (Pagina) -> List<TrattoPdf>,
    ) {
        val originali = if (allegato) staccaPagine(doc) else emptyList()
        val stati = HashMap<StilePdf, PDExtendedGraphicsState>()

        // Larghezza delle pagine nuove: quella (visibile) della prima pagina del PDF usata dalla nota.
        val riferimento = pagine.firstNotNullOfOrNull { originali.getOrNull(it.pdfPagina) } ?: originali.firstOrNull()
        val larghezzaNuove = riferimento?.let { larghezzaVisibile(it) } ?: A4_LARGHEZZA_PT

        // Per le pagine del PDF usate piu' volte si tiene una copia intatta, presa prima di aggiungere
        // i tratti: le copie successive partono da quella, non dalla pagina gia' disegnata.
        val stampi = HashMap<Int, PDPage>()
        for ((indice, quante) in pagine.groupingBy { it.pdfPagina }.eachCount()) {
            if (quante > 1) originali.getOrNull(indice)?.let { stampi[indice] = duplica(it) }
        }

        val usate = HashSet<Int>()
        var identica = pagine.size == originali.size
        for ((i, p) in pagine.withIndex()) {
            if (p.pdfPagina != i) identica = false
            val originale = if (p.pdfPagina >= 0) originali.getOrNull(p.pdfPagina) else null
            if (originale != null) {
                val pagina = if (usate.add(p.pdfPagina)) originale else duplica(stampi.getValue(p.pdfPagina))
                doc.addPage(pagina)
                val lista = tratti(p)
                if (lista.isEmpty()) continue
                val box = pagina.cropBox
                val t = Trasformazione.perPagina(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, pagina.rotation)
                // PdfBox allunga sul posto l'array /Contents: se fosse condiviso con altre pagine, i
                // tratti finirebbero anche li'.
                copiaArray(pagina.cosObject, COSName.CONTENTS)
                // APPEND + resetContext: il contenuto originale finisce tra q e Q e il nostro parte
                // dallo stato grafico iniziale, qualunque cosa lasci in giro la pagina.
                PDPageContentStream(doc, pagina, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    disegnaTratti(cs, lista, t, stati)
                }
            } else {
                val larghezza = if (allegato) larghezzaNuove else A4_LARGHEZZA_PT
                val altezza = altezzaPagina(larghezza, p.altezza)
                val pagina = PDPage(PDRectangle(larghezza, altezza))
                doc.addPage(pagina)
                val t = Trasformazione.perPagina(0f, 0f, larghezza, altezza)
                PDPageContentStream(doc, pagina).use { cs ->
                    disegnaSfondo(cs, p.sfondo, p.altezza, t)
                    disegnaTratti(cs, tratti(p), t, stati)
                }
            }
        }

        // Le pagine tolte non devono finire nel file, nemmeno come oggetti orfani: l'utente potrebbe
        // averle tolte proprio per non condividerle.
        val tolte = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        for ((i, p) in originali.withIndex()) if (i !in usate) tolte += p.cosObject
        scollega(doc, tolte)

        val catalogo = doc.documentCatalog.cosObject
        if (allegato) {
            // L'XMP originale dichiarerebbe un titolo vecchio e magari una conformita' (PDF/A) che non vale piu'.
            catalogo.removeItem(COSName.METADATA)
            // Le etichette delle pagine ("i", "ii", "1"...) non corrispondono piu' se le pagine sono cambiate.
            if (!identica) catalogo.removeItem(COSName.PAGE_LABELS)
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        }

        val infoDoc = doc.documentInformation
        infoDoc.title = titolo
        infoDoc.creator = "Tratto"
        infoDoc.modificationDate = Calendar.getInstance()
        doc.documentInformation = infoDoc
    }

    /**
     * Toglie tutte le pagine dall'albero del documento e le restituisce nell'ordine originale, con gli
     * attributi ereditati (risorse, MediaBox, CropBox, rotazione) copiati sulla pagina stessa: dopo non
     * avranno piu' i genitori da cui ereditarli.
     */
    private fun staccaPagine(doc: PDDocument): List<PDPage> {
        val pagine = doc.pages.toList()
        val ereditabili = arrayOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)
        for (p in pagine) {
            val d = p.cosObject
            for (chiave in ereditabili) {
                if (d.containsKey(chiave)) continue
                PDPageTree.getInheritableAttribute(d, chiave)?.let { d.setItem(chiave, it) }
            }
        }
        val radice = COSDictionary()
        radice.setItem(COSName.TYPE, COSName.PAGES)
        radice.setItem(COSName.KIDS, COSArray())
        radice.setInt(COSName.COUNT, 0)
        doc.documentCatalog.cosObject.setItem(COSName.PAGES, radice)
        return pagine
    }

    /**
     * Toglie da tutto il documento i riferimenti alle pagine [tolte]: destinazioni con nome, segnalibri,
     * link, albero della struttura, campi dei moduli... Senza questo PdfBox le scriverebbe comunque,
     * con il loro contenuto, perche' qualcosa le raggiunge. I segnalibri e i link che portavano li'
     * restano, ma non vanno da nessuna parte.
     */
    private fun scollega(doc: PDDocument, tolte: Set<COSBase>) {
        if (tolte.isEmpty()) return
        for (p in tolte) (p as COSDictionary).removeItem(COSName.PARENT)
        // PdfBox 2.0 risolve tutti gli oggetti all'apertura: qui si scorre il grafo gia' in memoria.
        val visti = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pila = ArrayDeque<COSBase>()
        pila.addLast(doc.document.trailer)
        while (pila.isNotEmpty()) {
            val o = pila.removeLast()
            if (!visti.add(o)) continue
            when (o) {
                is COSDictionary -> for (chiave in o.keySet().toList()) {
                    val v = risolvi(o.getItem(chiave)) ?: continue
                    if (v in tolte) o.removeItem(chiave) else if (v is COSDictionary || v is COSArray) pila.addLast(v)
                }
                is COSArray -> for (i in 0 until o.size()) {
                    val v = risolvi(o.get(i)) ?: continue
                    if (v in tolte) o.set(i, COSNull.NULL) else if (v is COSDictionary || v is COSArray) pila.addLast(v)
                }
            }
        }
    }

    private fun risolvi(v: COSBase?): COSBase? = if (v is COSObject) v.`object` else v

    /** Copia di una pagina usata due volte: i content stream aggiunti a una non devono finire sull'altra. */
    private fun duplica(p: PDPage): PDPage {
        val d = COSDictionary(p.cosObject)
        copiaArray(d, COSName.CONTENTS)
        copiaArray(d, COSName.ANNOTS)
        return PDPage(d)
    }

    /** Sostituisce l'array [chiave] di [d] con una copia (gli elementi restano gli stessi oggetti). */
    private fun copiaArray(d: COSDictionary, chiave: COSName) {
        val a = d.getDictionaryObject(chiave) as? COSArray ?: return
        d.setItem(chiave, COSArray().apply { for (i in 0 until a.size()) add(a.get(i)) })
    }

    private fun larghezzaVisibile(p: PDPage): Float {
        val box = p.cropBox
        return Trasformazione.dimensioniVisibili(box.width, box.height, p.rotation).first
    }

    /** Altezza in punti di una pagina nuova larga [larghezza]: la pagina A4 di Tratto diventa A4 esatto. */
    fun altezzaPagina(larghezza: Float, altezzaUnita: Float): Float =
        if (larghezza == A4_LARGHEZZA_PT && abs(altezzaUnita - Foglio.ALTEZZA) < 0.5f) A4_ALTEZZA_PT
        else larghezza * altezzaUnita / Foglio.LARGHEZZA

    private fun disegnaSfondo(cs: PDPageContentStream, sfondo: Sfondo, altezza: Float, t: Trasformazione) {
        if (sfondo == Sfondo.BIANCO) return
        cs.setStrokingColor(
            ((COLORE_SFONDO shr 16) and 0xFF) / 255f, ((COLORE_SFONDO shr 8) and 0xFF) / 255f, (COLORE_SFONDO and 0xFF) / 255f,
        )
        val tr = Tracciato(cs)
        if (sfondo == Sfondo.PUNTINI) {
            // Un segmento lungo 0,01 pt con le estremita' tonde e' un cerchio pieno: ~25 byte a puntino
            // contro ~170 di un cerchio fatto con quattro curve. (Lungo zero non lo disegnano tutti.)
            val p = puntiniSfondo(altezza)
            cs.setLineCapStyle(1)
            cs.setLineWidth(arrotonda(2f * RAGGIO_PUNTINI * t.scala))
            for (i in 0 until p.size / 2) {
                val x = centesimi(t.x(p[2 * i], p[2 * i + 1]))
                val y = centesimi(t.y(p[2 * i], p[2 * i + 1]))
                tr.punto(x, y, 'm')
                tr.punto(x + 1, y, 'l')
            }
        } else {
            val l = lineeSfondo(sfondo, altezza)
            cs.setLineWidth(SPESSORE_RIGHE_PT)
            for (i in 0 until l.size / 4) {
                val o = 4 * i
                tr.punto(centesimi(t.x(l[o], l[o + 1])), centesimi(t.y(l[o], l[o + 1])), 'm')
                tr.punto(centesimi(t.x(l[o + 2], l[o + 3])), centesimi(t.y(l[o + 2], l[o + 3])), 'l')
            }
        }
        tr.traccia()
    }

    private fun disegnaTratti(
        cs: PDPageContentStream,
        tratti: List<TrattoPdf>,
        t: Trasformazione,
        stati: HashMap<StilePdf, PDExtendedGraphicsState>,
    ) {
        if (tratti.isEmpty()) return
        val tr = Tracciato(cs)
        for (gruppo in raggruppa(tratti.map { it.stile })) {
            val stile = tratti[gruppo.first].stile
            val trasparente = !stile.unibile
            if (trasparente) {
                cs.saveGraphicsState()
                cs.setGraphicsStateParameters(stati.getOrPut(stile.copy(rgb = 0)) { statoGrafico(stile) })
            }
            cs.setNonStrokingColor(
                ((stile.rgb shr 16) and 0xFF) / 255f, ((stile.rgb shr 8) and 0xFF) / 255f, (stile.rgb and 0xFF) / 255f,
            )
            for (i in gruppo) {
                for (contorno in tratti[i].contorni) scriviContorno(tr, contorno, t)
                // Con la trasparenza ogni tratto e' un riempimento a se': dove due tratti si
                // sovrappongono il colore si somma, come a schermo.
                if (trasparente) tr.riempi()
            }
            tr.riempi()
            if (trasparente) cs.restoreGraphicsState()
        }
    }

    private fun statoGrafico(stile: StilePdf) = PDExtendedGraphicsState().apply {
        nonStrokingAlphaConstant = stile.alfa / 255f
        if (stile.moltiplica) blendMode = BlendMode.MULTIPLY
    }

    /**
     * Aggiunge al tracciato un contorno chiuso: vertici portati in punti, semplificati e arrotondati al
     * centesimo, senza i doppioni consecutivi. Il riempimento (f, regola nonzero) chiude da solo i
     * sottotracciati, quindi niente `h`.
     *
     * Tutti i contorni vengono scritti nello stesso verso: unendo piu' tratti in un solo riempimento
     * nonzero, due contorni in versi opposti si annullerebbero dove si incrociano (buchi bianchi).
     */
    private fun scriviContorno(tr: Tracciato, xy: FloatArray, t: Trasformazione) {
        val n = xy.size / 2
        if (n < 3) return
        val pt = FloatArray(n * 2)
        for (i in 0 until n) {
            val u = xy[2 * i]; val v = xy[2 * i + 1]
            pt[2 * i] = t.x(u, v)
            pt[2 * i + 1] = t.y(u, v)
        }
        val s = semplifica(pt, TOLLERANZA_PT)
        val m = s.size / 2
        val inverti = areaConSegno(s) < 0
        var px = Long.MIN_VALUE
        var py = Long.MIN_VALUE
        var primo = true
        for (k in 0 until m) {
            val i = if (inverti) m - 1 - k else k
            val x = centesimi(s[2 * i]); val y = centesimi(s[2 * i + 1])
            if (x == px && y == py) continue
            tr.punto(x, y, if (primo) 'm' else 'l')
            px = x; py = y
            primo = false
        }
    }

    /**
     * Scrive i tracciati direttamente nel content stream, con i numeri al centesimo e senza zeri
     * inutili: con moveTo/lineTo PdfBox userebbe 5 decimali (841.89001), quasi il doppio dei byte.
     */
    private class Tracciato(private val cs: PDPageContentStream) {
        private val sb = StringBuilder()
        private var vuoto = true

        /** Un vertice in centesimi di punto con l'operatore [op] ('m' o 'l'). */
        fun punto(x: Long, y: Long, op: Char) {
            sb.numeroPdf(x).append(' ').numeroPdf(y).append(' ').append(op).append('\n')
            vuoto = false
            if (sb.length > 64 * 1024) scarica()
        }

        @Suppress("DEPRECATION") // appendRawCommands e' "sconsigliato" solo perche' non controlla cosa si scrive.
        private fun scarica() {
            if (sb.isEmpty()) return
            cs.appendRawCommands(sb.toString())
            sb.setLength(0)
        }

        /** Riempie (nonzero) quanto scritto finora, se c'e' qualcosa. */
        fun riempi() {
            if (vuoto) return
            scarica()
            cs.fill()
            vuoto = true
        }

        /** Traccia le linee scritte finora, se ce ne sono. */
        fun traccia() {
            if (vuoto) return
            scarica()
            cs.stroke()
            vuoto = true
        }
    }
}
