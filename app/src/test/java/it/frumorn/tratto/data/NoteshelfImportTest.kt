package it.frumorn.tratto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class NoteshelfImportTest {
    private fun segment(x: Float, y: Float, larghezza: Int, opacita: Int = 100): ByteArray =
        java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putFloat(x).putFloat(y).putShort(larghezza.toShort()).putShort(opacita.toShort()).putInt(0).array()

    @Test
    fun unSegmentDevientUnPoint() {
        val blob = byteArrayOf(
            0x40, 0xd6.toByte(), 0x4b, 0x43, 0x00, 0x72, 0xf7.toByte(), 0x42,
            0x67, 0x01, 0x64, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        // 0x0167 = 359 → largeur 3,59 (pas un temps) ; 0x0064 = opacité 100.
        val t = trattoDaSegmenti(blob, scala = 1f, colore = coloreArgb(0x13326B), spessore = 2f, penna = Penna.PENNA, id = 7)!!
        assertEquals(7L, t.id)
        assertEquals(Penna.PENNA, t.penna)
        assertEquals(0xFF13326B.toInt(), t.colore)
        assertEquals(1, t.quanti)
        assertEquals(203.83691f, t.punti[0], 0.02f)
        assertEquals(123.72266f, t.punti[1], 0.02f)
        assertEquals(1f, t.punti[2], 0.001f)
        assertEquals(0f, t.punti[5], 0.001f)
        assertEquals(3.59f / PENNA_PIENA, t.spessore, 0.01f)
    }

    @Test
    fun laLargeurMontePuisRedescend() {
        val blob = segment(0f, 0f, 350) + segment(1f, 0f, 450) + segment(2f, 0f, 320)
        val t = trattoDaSegmenti(blob, 1f, 0xFF000000.toInt(), 2f, Penna.PENNA, 1)!!
        assertEquals(3, t.quanti)
        assertEquals(4.5f / PENNA_PIENA, t.spessore, 0.01f)
        // Pression max au milieu (plus large), plus faible en fin de trait.
        assertTrue(t.punti[1 * Tratto.CAMPI + 2] > t.punti[0 * Tratto.CAMPI + 2])
        assertTrue(t.punti[1 * Tratto.CAMPI + 2] > t.punti[2 * Tratto.CAMPI + 2])
        assertEquals(listOf(0f, MS_PER_PUNTO, 2 * MS_PER_PUNTO), (0 until 3).map { t.punti[it * Tratto.CAMPI + 5] })
    }

    @Test
    fun leRubanGardeSaLargeur() {
        val t = trattoDaSegmenti(segment(0f, 0f, 350) + segment(5f, 0f, 350), 1f, 0xFF000000.toInt(), 20f, Penna.EVIDENZIATORE, 1, epaisseurFissa = true)!!
        assertEquals(20f, t.spessore, 0.001f)
        assertEquals(1f, t.punti[2], 0.001f)
    }

    @Test
    fun uneLigneDevientUnTrait() {
        val punti = java.util.Base64.getEncoder().encodeToString("""{"controlPoints":[[10.0,20.0],[30.0,20.0]],"properties":{"strokeThickness":4.5}}""".toByteArray())
        val json = """{"shapeData":"$punti"}"""
        val t = trattoDaForma(json, scala = 1f, colore = 0xFF000000.toInt(), spessore = 2f, id = 1)!!
        assertEquals(2, t.quanti)
        assertEquals(10f, t.punti[0], 0.01f)
        assertEquals(20f, t.punti[1], 0.01f)
        assertEquals(30f, t.punti[6], 0.01f)
        assertEquals(4.5f / PENNA_PIENA, t.spessore, 0.01f)
    }

    @Test
    fun lePaysagePermuteLargeurEtHauteur() {
        assertEquals(2304f to 1440f, dimensioniPagina(1440f, 2304f, 90.0))
        assertEquals(2304f to 1440f, dimensioniPagina(1440f, 2304f, 270.0))
        assertEquals(2304f to 1440f, dimensioniPagina(1440f, 2304f, -90.0))
        assertEquals(1440f to 2304f, dimensioniPagina(1440f, 2304f, 0.0))
        assertEquals(1440f to 2304f, dimensioniPagina(1440f, 2304f, 180.0))
    }

    @Test
    fun unePageTropEtroiteEstElargie() {
        val punti = FloatArray(Tratto.CAMPI * 2)
        punti[0] = 100f; punti[1] = 50f
        punti[Tratto.CAMPI] = 2000f; punti[Tratto.CAMPI + 1] = 800f
        val t = Tratto(1, Penna.PENNA, 0xFF000000.toInt(), 2f, punti)
        val p = Pagina("p", Sfondo.BIANCO, larghezza = 1440f, altezza = 2304f)
        val r = riparaDimensioniPagina(p, listOf(t))!!
        assertEquals(2304f, r.larghezza, 0.1f)
        assertEquals(1440f, r.altezza, 0.1f)
        assertEquals(null, riparaDimensioniPagina(p, listOf(Tratto(1, Penna.PENNA, 0, 2f, floatArrayOf(10f, 10f, 1f, -1f, -1f, 0f)))))
    }

    @Test
    fun unePageNoteshelfEstRameneeA1000() {
        val punti = floatArrayOf(1440f, 2304f, 1f, -1f, -1f, 0f)
        val t = Tratto(1, Penna.PENNA, 0xFF000000.toInt(), 2.88f, punti)
        val p = Pagina("p", Sfondo.BIANCO, larghezza = 1440f, altezza = 2304f)
        val n = normalizzaPaginaAFoglio(p, listOf(t))!!
        assertEquals(Foglio.LARGHEZZA, n.pagina.larghezza, 0.01f)
        assertEquals(Foglio.LARGHEZZA * 2304f / 1440f, n.pagina.altezza, 0.01f)
        assertEquals(Foglio.LARGHEZZA, n.tratti[0].punti[0], 0.01f)
        assertEquals(Foglio.LARGHEZZA * 2304f / 1440f, n.tratti[0].punti[1], 0.01f)
        assertEquals(2f, n.tratti[0].spessore, 0.01f)
        assertEquals(null, normalizzaPaginaAFoglio(Pagina("ok", Sfondo.BIANCO), emptyList()))
    }

    @Test
    fun lePaysagePuisLaNormalisationGardentLesProportions() {
        val punti = floatArrayOf(2180f, 800f, 1f, -1f, -1f, 0f)
        val t = Tratto(1, Penna.PENNA, 0xFF000000.toInt(), 2.88f, punti)
        val p = Pagina("p", Sfondo.BIANCO, larghezza = 1440f, altezza = 2304f)
        val n = normalizzaPaginaAFoglio(p, listOf(t))!!
        assertEquals(Foglio.LARGHEZZA, n.pagina.larghezza, 0.01f)
        assertEquals(Foglio.LARGHEZZA * 1440f / 2304f, n.pagina.altezza, 0.01f)
        assertEquals(2180f * Foglio.LARGHEZZA / 2304f, n.tratti[0].punti[0], 0.05f)
    }

    @Test
    fun lePlistDonneLesPages() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>pages</key>
              <array>
                <dict>
                  <key>uuid</key><string>ABC</string>
                  <key>isCover</key><true/>
                  <key>pdfKitPageRect</key><string>{{0.0, 0.0}}, {1440.0, 1920.0}</string>
                </dict>
              </array>
            </dict></plist>
        """.trimIndent()
        val radice = leggiPlist(ByteArrayInputStream(xml.toByteArray())) as Map<*, *>
        val page = (radice["pages"] as List<*>).first() as Map<*, *>
        assertEquals("ABC", page["uuid"])
        assertEquals(true, page["isCover"])
        assertTrue(page["pdfKitPageRect"].toString().contains("1440"))
    }
}
