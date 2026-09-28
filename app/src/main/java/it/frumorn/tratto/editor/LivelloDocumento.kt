package it.frumorn.tratto.editor

import android.graphics.Canvas
import it.frumorn.tratto.data.TestoRicco

/**
 * Il contenuto di un documento esterno (Google Docs, nella versione completa) disegnato sulle pagine
 * della nota sotto l'inchiostro, come la pagina di un PDF. Il documento e' gia' impaginato: [pagine]
 * pagine alte [altezza] unita' (larghe 1000, come tutte le pagine di Tratto).
 */
interface LivelloDocumento {
    val pagine: Int
    val altezza: Float

    /** Disegna la pagina [indice]; il canvas e' gia' in unita' di pagina. [pxPerUnita] serve a scegliere la risoluzione delle immagini. */
    fun disegna(c: Canvas, indice: Int, pxPerUnita: Float)

    /**
     * Il paragrafo del documento sotto il punto ([x], [y] in unita' della pagina [indice]), da
     * modificare; sotto la fine del testo, un paragrafo nuovo in fondo. Null se li' non si puo' scrivere.
     */
    fun paragrafo(indice: Int, x: Float, y: Float): ParagrafoDocumento?
}

/** Un paragrafo del documento da modificare: dove sta (pagina [pagina], unita' di pagina) e il suo testo. */
class ParagrafoDocumento(val pagina: Int, val x: Float, val y: Float, val larghezza: Float, val contenuto: TestoRicco, val rif: Any)

/** Una modifica in corso di un paragrafo del documento: il testo va al documento mentre si scrive. */
interface ModificaDocumento {
    /** Il testo e' cambiato: si manda al documento poco dopo (alla prima pausa). */
    fun cambiato(t: TestoRicco)

    /** Fine della modifica: si manda l'ultima versione e il paragrafo torna a disegnarsi dal documento. */
    fun fine(t: TestoRicco)
}

/**
 * Il documento esterno collegato alla nota aperta nell'editor: si aggiorna da solo mentre la nota e'
 * aperta e l'app e' in primo piano.
 */
interface DocumentoAperto {
    /** L'app va in secondo piano: si smette di chiedere aggiornamenti. */
    fun pausa()

    /** L'app torna in primo piano. */
    fun riprendi()

    /** L'editor si chiude. */
    fun chiudi()

    /** Comincia a modificare [p] (dato da [LivelloDocumento.paragrafo] di questo documento), o null se ora non si puo'. */
    fun modifica(p: ParagrafoDocumento): ModificaDocumento?
}
