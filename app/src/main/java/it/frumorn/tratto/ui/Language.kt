package it.frumorn.tratto.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.frumorn.tratto.R
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.data.Penna

/**
 * Francese, italiano o inglese. Vuoto: la lingua del dispositivo, e l'inglese se non è una delle tre.
 * Android applica la scelta a tutta l'app e la conserva.
 *
 * I testi dell'interfaccia stanno in `res/values/strings.xml` (inglese),
 * `values-fr/strings.xml` e `values-it/strings.xml`.
 */
enum class Language(val tag: String?) {
    SISTEMA(null),
    FR("fr"),
    IT("it"),
    EN("en"),
}

object Lingue {
    fun scelta(context: Context): Language {
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return Language.SISTEMA
        return when (locales[0].language) {
            "fr" -> Language.FR
            "it" -> Language.IT
            else -> Language.EN
        }
    }

    fun applica(context: Context, lingua: Language) {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = if (lingua.tag == null) LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(lingua.tag)
    }
}

/** Testo a schermo da `strings.xml`, nella lingua dell'app. */
@Composable
fun testo(@StringRes id: Int, vararg formatArgs: Any?): String {
    val args = formatArgs.map { it ?: "" }.toTypedArray()
    return if (args.isEmpty()) stringResource(id) else stringResource(id, *args)
}

/** Come [testo], fuori da Compose (toast, worker, messaggi di errore). */
fun testoOra(@StringRes id: Int, vararg formatArgs: Any?): String {
    val args = formatArgs.map { it ?: "" }.toTypedArray()
    return TrattoApp.instance.getString(id, *args)
}

@Composable
fun nomePenna(p: Penna): String = when (p) {
    Penna.STILOGRAFICA -> testo(R.string.fountain_pen)
    Penna.PENNA -> testo(R.string.pen)
    Penna.MATITA -> testo(R.string.pencil)
    Penna.EVIDENZIATORE -> testo(R.string.highlighter)
    Penna.PENNELLO -> testo(R.string.brush)
}
