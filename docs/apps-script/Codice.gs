/**
 * Tratto: i disegni fatti sul tablet, nel documento Google davanti al testo.
 *
 * L'API di Google Docs inserisce solo immagini in linea; Apps Script sa ancorare un'immagine a un
 * paragrafo, davanti al testo e in un punto preciso. Tratto chiama questa funzione con l'API Apps
 * Script (scripts.run), con il suo stesso accesso a Google.
 *
 * Ogni disegno arriva con il nome di un intervallo che Tratto ha appena messo sul paragrafo a cui
 * ancorarlo (cosi' il paragrafo si ritrova anche se nel frattempo qualcuno ha scritto nel documento),
 * il PNG in base64, le misure e la posizione rispetto al paragrafo, in punti.
 *
 * Restituisce, nello stesso ordine, l'id di ogni immagine nel documento (null se il paragrafo non
 * c'era piu').
 */
function aggiungiDisegni(documentoId, disegni) {
  var doc = DocumentApp.openById(documentoId);
  var ids = [];
  for (var i = 0; i < disegni.length; i++) {
    var d = disegni[i];
    var intervalli = doc.getNamedRanges(d.ancora);
    if (intervalli.length === 0) { ids.push(null); continue; }
    var paragrafo = paragrafoDi(intervalli[0].getRange().getRangeElements()[0].getElement());
    for (var k = 0; k < intervalli.length; k++) intervalli[k].remove();
    if (paragrafo === null) { ids.push(null); continue; }
    var png = Utilities.newBlob(Utilities.base64Decode(d.png), 'image/png', 'tratto.png');
    var img = paragrafo.addPositionedImage(png)
      .setLayout(DocumentApp.PositionedLayout.ABOVE_TEXT)
      .setWidth(d.larghezza)
      .setHeight(d.altezza)
      .setLeftOffset(d.sinistra)
      .setTopOffset(d.alto);
    ids.push(img.getId());
  }
  doc.saveAndClose();
  return ids;
}

/** Il paragrafo (o la voce di elenco) che contiene [el]. */
function paragrafoDi(el) {
  while (el !== null) {
    var t = el.getType();
    if (t === DocumentApp.ElementType.PARAGRAPH || t === DocumentApp.ElementType.LIST_ITEM) return el;
    el = el.getParent();
  }
  return null;
}
