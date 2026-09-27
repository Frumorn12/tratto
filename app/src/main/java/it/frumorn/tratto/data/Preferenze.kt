package it.frumorn.tratto.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import it.frumorn.tratto.editor.ImpostazioniPenna
import it.frumorn.tratto.editor.ModoGomma
import it.frumorn.tratto.editor.StatoStrumenti
import it.frumorn.tratto.editor.Strumento
import it.frumorn.tratto.scrittura.Stile
import it.frumorn.tratto.ui.theme.Tema

enum class DoppioClic { NUOVA_NOTA, GOMMA, NIENTE }

/** Impostazioni dell'app, osservabili da Compose e salvate subito. */
class Preferenze(context: Context) {
    private val sp = context.getSharedPreferences("impostazioni", Context.MODE_PRIVATE)

    var tema by mutableStateOf(runCatching { Tema.valueOf(sp.getString("tema", null)!!) }.getOrDefault(Tema.SISTEMA))
        private set
    var spigoloVivo by mutableStateOf(sp.getBoolean("spigolo", false))
        private set
    var sensibilita by mutableStateOf(sp.getFloat("sensibilita", 1f))
        private set
    var disegnaConDita by mutableStateOf(sp.getBoolean("dita", false))
        private set
    var doppioClic by mutableStateOf(runCatching { DoppioClic.valueOf(sp.getString("doppioClic", null)!!) }.getOrDefault(DoppioClic.NUOVA_NOTA))
        private set
    var sfondoPredefinito by mutableStateOf(runCatching { Sfondo.valueOf(sp.getString("sfondo", null)!!) }.getOrDefault(Sfondo.RIGHE))
        private set
    /** Stile con cui la "bella scrittura" riscrive il testo trascritto. */
    var stileBellaScrittura by mutableStateOf(runCatching { Stile.valueOf(sp.getString("bellaScrittura", null)!!) }.getOrDefault(Stile.CORSIVO))
        private set

    fun impostaTema(v: Tema) { tema = v; sp.edit { putString("tema", v.name) } }
    fun impostaSpigolo(v: Boolean) { spigoloVivo = v; sp.edit { putBoolean("spigolo", v) } }
    fun impostaSensibilita(v: Float) { sensibilita = v; sp.edit { putFloat("sensibilita", v) } }
    fun impostaDita(v: Boolean) { disegnaConDita = v; sp.edit { putBoolean("dita", v) } }
    fun impostaDoppioClic(v: DoppioClic) { doppioClic = v; sp.edit { putString("doppioClic", v.name) } }
    fun impostaSfondo(v: Sfondo) { sfondoPredefinito = v; sp.edit { putString("sfondo", v.name) } }
    fun impostaStileBellaScrittura(v: Stile) { stileBellaScrittura = v; sp.edit { putString("bellaScrittura", v.name) } }

    /** Ultimi strumenti usati, cosi' una nota nuova riparte con la stessa penna. */
    fun strumenti(): StatoStrumenti {
        val base = StatoStrumenti()
        val penne = base.penne.mapValues { (p, def) ->
            ImpostazioniPenna(sp.getInt("colore_${p.name}", def.colore), sp.getFloat("spessore_${p.name}", def.spessore))
        }
        return base.copy(
            strumento = Strumento.PENNA,
            penna = runCatching { Penna.valueOf(sp.getString("penna", null)!!) }.getOrDefault(Penna.PENNA),
            penne = penne,
            modoGomma = runCatching { ModoGomma.valueOf(sp.getString("modoGomma", null)!!) }.getOrDefault(ModoGomma.TRATTO),
            raggioGomma = sp.getFloat("raggioGomma", base.raggioGomma),
            coloriRecenti = sp.getString("recenti", "")!!.split(',').mapNotNull { it.toIntOrNull() },
        )
    }

    fun salvaStrumenti(s: StatoStrumenti) = sp.edit {
        putString("penna", s.penna.name)
        s.penne.forEach { (p, imp) -> putInt("colore_${p.name}", imp.colore); putFloat("spessore_${p.name}", imp.spessore) }
        putString("modoGomma", s.modoGomma.name)
        putFloat("raggioGomma", s.raggioGomma)
        putString("recenti", s.coloriRecenti.joinToString(","))
    }
}
