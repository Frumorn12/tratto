package it.frumorn.tratto.editor

import android.view.MotionEvent

/**
 * La penna in mezzo alle dita e al palmo, e la sua pressione. Con la penna inclinata la mano si
 * appoggia prima e di piu': il palmo puo' toccare lo schermo prima della punta o restarci quando la
 * punta si alza, e il tablet a volte da' pressione zero anche con la punta sul vetro.
 */
internal object EventiPenna {
    /** L'evento visto dalla penna: [azione] (DOWN, MOVE, UP o CANCEL) e l'indice del suo puntatore (-1 se non c'e' piu'). */
    data class Smistamento(val azione: Int, val indice: Int)

    /** Un evento con la penna dentro che non le cambia niente: il palmo che si appoggia o si alza. */
    val IGNORA = Smistamento(-1, -1)

    fun penna(strumento: Int) = strumento == MotionEvent.TOOL_TYPE_STYLUS || strumento == MotionEvent.TOOL_TYPE_ERASER

    /**
     * Cosa fa la penna nell'evento [azione] (azione mascherata, [indiceAzione] come actionIndex) con
     * puntatori di tipo [strumenti] e id [ids]. [attiva] e' l'id del puntatore della penna se e' giu',
     * altrimenti -1. Null se l'evento non e' della penna: le dita lo trattano come sempre.
     */
    fun smista(azione: Int, indiceAzione: Int, strumenti: IntArray, ids: IntArray, attiva: Int): Smistamento? {
        // Un DOWN e' sempre un gesto nuovo: se si credeva la penna giu', l'alzata e' andata persa.
        if (attiva >= 0 && azione != MotionEvent.ACTION_DOWN) {
            val k = ids.indexOf(attiva)
            if (k < 0) return Smistamento(MotionEvent.ACTION_CANCEL, -1)
            return when (azione) {
                MotionEvent.ACTION_MOVE -> Smistamento(MotionEvent.ACTION_MOVE, k)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (indiceAzione == k) Smistamento(MotionEvent.ACTION_UP, k) else IGNORA
                MotionEvent.ACTION_CANCEL -> Smistamento(MotionEvent.ACTION_CANCEL, k)
                else -> IGNORA
            }
        }
        return when (azione) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                if (penna(strumenti[indiceAzione])) Smistamento(MotionEvent.ACTION_DOWN, indiceAzione) else null
            else -> null
        }
    }

    /**
     * Il doppio controllo sulla pressione: con la punta sul vetro, un campione a pressione zero non e'
     * la penna che si alza ma il tablet che con la penna inclinata non la misura. Allora vale l'ultima
     * pressione vera del tratto (o [PREDEFINITA] se non ce n'e' ancora una): il tratto non si
     * assottiglia di colpo e un tratto tutto a pressione zero si vede lo stesso.
     */
    class PressioneTenuta {
        private var ultima = PREDEFINITA

        /** Un tratto nuovo. */
        fun inizia() {
            ultima = PREDEFINITA
        }

        fun daCorreggere(p: Float) = p <= SOGLIA

        fun correggi(p: Float): Float {
            if (daCorreggere(p)) return ultima
            ultima = p
            return p
        }

        companion object {
            const val PREDEFINITA = 0.35f
            const val SOGLIA = 0.005f
        }
    }
}
