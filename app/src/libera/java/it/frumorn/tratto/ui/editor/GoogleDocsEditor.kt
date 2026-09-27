package it.frumorn.tratto.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Google Docs nella versione libera: non c'e' (vedi GoogleDocs). Stesse funzioni usate dalla barra
 * dell'editor nella versione completa, ma non mostrano niente.
 */
class ControlloDocs internal constructor()

@Composable
fun rememberGoogleDocs(sessione: SessioneEditor): ControlloDocs = remember { ControlloDocs() }

/** Nessuna nota e' collegata: niente pallino. */
@Composable
fun ChipGoogleDocs(docs: ControlloDocs) = Unit

/** Nessuna voce nel menu. */
@Composable
fun VociGoogleDocs(docs: ControlloDocs, chiudiMenu: () -> Unit) = Unit
