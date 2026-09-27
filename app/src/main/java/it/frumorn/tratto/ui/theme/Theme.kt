package it.frumorn.tratto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

enum class Tema { SISTEMA, CHIARO, SCURO }

@Composable
fun TrattoTheme(
    tema: Tema = Tema.SISTEMA,
    spigoloVivo: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scuro = when (tema) {
        Tema.SISTEMA -> isSystemInDarkTheme()
        Tema.CHIARO -> false
        Tema.SCURO -> true
    }
    CompositionLocalProvider(LocalTrattoColors provides if (scuro) DarkExtra else LightExtra) {
        val schema = if (scuro) TrattoDark else TrattoLight
        MaterialTheme(
            colorScheme = schema,
            typography = TrattoType,
            shapes = if (spigoloVivo) ShapesSpigolo else ShapesArrotondato,
        ) {
            // Il testo fuori da una Surface prende comunque il colore giusto per il tema.
            CompositionLocalProvider(LocalContentColor provides schema.onBackground, content = content)
        }
    }
}
