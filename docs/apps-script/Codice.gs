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
 * [togli] (facoltativo) sono i disegni vecchi da togliere nella stessa esecuzione: l'id
 * dell'immagine e il nome di un intervallo sul paragrafo a cui e' ancorata (o null). Chi guarda il
 * documento vede il disegno nuovo prendere il posto del vecchio in un colpo solo.
 *
 * Senza [togli] restituisce, nello stesso ordine, l'id di ogni immagine messa (null se il paragrafo
 * non c'era piu'), come la prima versione. Con [togli] restituisce { ids: quella lista, tolti: gli
 * id delle immagini tolte }.
 */
function aggiungiDisegni(documentoId, disegni, togli) {
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
  var tolti = [];
  if (togli) {
    for (var j = 0; j < togli.length; j++) {
      if (togliImmagine(doc, togli[j])) tolti.push(togli[j].id);
    }
  }
  doc.saveAndClose();
  return togli ? { ids: ids, tolti: tolti } : ids;
}

/**
 * Toglie l'immagine [t.id]: prima dal paragrafo dell'intervallo [t.ancora], se no da qualsiasi
 * paragrafo del corpo. Vero se l'ha tolta.
 */
function togliImmagine(doc, t) {
  var paragrafo = null;
  if (t.ancora) {
    var intervalli = doc.getNamedRanges(t.ancora);
    if (intervalli.length > 0) paragrafo = paragrafoDi(intervalli[0].getRange().getRangeElements()[0].getElement());
    for (var k = 0; k < intervalli.length; k++) intervalli[k].remove();
  }
  if (paragrafo !== null && togliDa(paragrafo, t.id)) return true;
  var paragrafi = doc.getBody().getParagraphs();
  for (var i = 0; i < paragrafi.length; i++) {
    if (togliDa(paragrafi[i], t.id)) return true;
  }
  // Non trovata: se c'e' ancora da qualche parte (o era gia' sparita) ci pensa Tratto con l'API di Docs.
  return false;
}

function togliDa(paragrafo, id) {
  try {
    return paragrafo.removePositionedImage(id) === true;
  } catch (e) {
    return false;
  }
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
