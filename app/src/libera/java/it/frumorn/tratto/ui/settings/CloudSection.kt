package it.frumorn.tratto.ui.settings

import androidx.compose.runtime.Composable
import it.frumorn.tratto.ui.AppState

/**
 * Version libre : l'auto-export dossier + historique est dans [BackupSection].
 * Cette section reste pour la même signature que la version complète (Drive).
 */
@Composable
internal fun CloudSection(stato: AppState, lavoro: String?, impostaLavoro: (String?) -> Unit) = Unit
