# Nota in Google Docs, dal vivo

Una nota di Tratto si può collegare a un documento Google Docs. Chi apre il documento su un computer lo vede riempirsi mentre si scrive sul tablet, un secondo dopo ogni pausa. Il documento non contiene fotografie delle pagine. Ogni riga scritta a mano, ogni formula e ogni disegno è un'immagine trasparente ritagliata attorno all'inchiostro. Ogni casella di testo diventa testo vero, con il suo formato.

Il documento è condiviso: **quello che si scrive o si aggiunge direttamente in Google Docs resta**. Tratto tocca solo i suoi blocchi.

C'è solo nella versione **completa** (serve l'accesso all'account Google di Play services). Nella versione **libera** le voci non compaiono.

Il codice è nel pacchetto `it.frumorn.tratto.googledocs` di `src/completa`.

---

## 1. Come si usa

Nel menu ⋮ dell'editor, sezione **Google Docs**:

- **Collega a Google Docs**:
  - usa lo stesso accesso a Google del backup su Drive (scope `drive.file`). Se l'accesso non c'è ancora, compare la schermata di consenso di Google, la stessa di «Collega Google Drive» nelle impostazioni;
  - salva la nota, crea il documento nella cartella «Tratto» di Drive con il titolo della nota e le pagine A4, e lo riempie subito.
- **Apri in Google Docs** apre `https://docs.google.com/document/d/<id>/edit` (nell'app Google Docs o nel browser). Da lì si condivide il documento con chi deve guardarlo.
- **Scollega da Google Docs**: la nota smette di aggiornare il documento, che resta su Drive com'era.

Accanto al titolo, solo per le note collegate, c'è un piccolo **«Docs»** con un pallino:

| Pallino | Significato |
|---|---|
| grigio | collegata, niente di nuovo da quando l'app è aperta |
| viola chiaro | ci sono modifiche, partono tra un attimo |
| viola che pulsa | aggiornamento in corso |
| verde | documento aggiornato |
| ambra | non è andata per un problema passeggero (rete, Google occupato); si riprova da soli, anche ad app chiusa |
| rosso | errore; se Google vuole di nuovo il consenso, toccando si ricollega |

Un tocco sul pallino apre il documento. Con il pallino ambra o rosso mostra il motivo e riprova.

Collegare una nota collega anche Google Drive nelle impostazioni (stesso account, stesso permesso), senza accendere il backup automatico. Se si scollega Google Drive dalle impostazioni, l'accesso viene revocato e il pallino diventa rosso finché non si ricollega.

---

## 2. Quando si aggiorna

- L'editor salva un secondo dopo l'ultimo tratto. `GoogleDocs.modificata` fa partire l'aggiornamento **un secondo dopo l'ultima modifica**: mentre si scrive senza pause non parte niente, alla prima pausa il documento si aggiorna.
- Si mandano solo i blocchi cambiati. Una riga toccata costa un PNG di qualche decina di KB; una casella di testo cambiata non costa immagini.
- Un aggiornamento già partito non si interrompe. Se nel frattempo arrivano altre modifiche, ne parte un altro subito dopo.
- Alla **chiusura dell'editor** e quando l'app va **in secondo piano**, se ci sono modifiche non ancora mandate, `GoogleDocs.finisciInBackground` le affida a `DocsWorker` (WorkManager, un lavoro per nota, rete richiesta). Il lavoro finisce anche ad app chiusa e, senza rete, parte appena la rete torna.
- Senza rete anche l'aggiornamento dall'app passa a `DocsWorker`.
- Gli aggiornamenti vanno uno alla volta, per tutte le note (un `Mutex`).

---

## 3. Com'è fatto il documento

```
Titolo della nota               testo, stile TITLE
[riga 1]                        immagine trasparente, ritagliata
[riga 2]
Testo di una casella            testo vero: stili, grassetto, colori, elenchi
[disegno]
...                             (poi le pagine dopo, una dopo l'altra)
```

### Blocchi (`BlocchiPagina`)

Ogni pagina si divide in blocchi, dall'alto in basso:

- **Figure**. I tratti e le immagini incollate si raccolgono in fasce orizzontali: due elementi stanno nella stessa fascia se in verticale si sovrappongono o distano meno di 7 unità (circa 4 punti su un A4). Così una riga con i suoi accenti, i puntini e il segno di uguale è una fascia sola; due righe separate dall'interlinea sono due fasce; un disegno alto è una fascia sola. La fascia si disegna in un PNG trasparente ritagliato al suo ingombro, senza carta né righe (`EsportaPdf.immagineRitaglio`).
- **Testi**. Ogni casella di testo è un blocco a sé, messo in ordine con le fasce secondo il suo bordo alto. Diventa testo vero: gli stili di paragrafo vanno sugli stili di Documenti (Titolo, Sottotitolo, Intestazione 1-3), e restano allineamento, elenchi puntati e numerati (con il livello), grassetto, corsivo, sottolineato, barrato, colore, evidenziatore, dimensione e carattere (Manrope, Noto Serif, Roboto Mono).
- **Pagine di PDF**. Restano un'immagine intera, con il PDF sotto e tutto quello che c'è sopra.

La **chiave** di una figura è il suo tratto più vecchio (gli id dei tratti crescono): aggiungere un tratto a una riga la ridisegna senza spostarla. La chiave di un testo è l'id della casella.

### Misure

- La pagina di Tratto, larga 1000 unità, occupa 440 punti del documento: sta nei margini normali di un A4. Una riga larga metà pagina diventa un'immagine larga 220 punti.
- Il rientro da sinistra del paragrafo è la distanza del blocco dal bordo della pagina. Lo spazio sopra è la distanza dal blocco precedente (al massimo 48 punti). Il primo blocco di ogni pagina dopo la prima ha 24 punti sopra. Le misure sono arrotondate al punto: uno spostamento minimo non costa un aggiornamento.
- I PNG hanno 2 pixel per unità, circa 330 dpi nel documento.
- Le dimensioni del testo delle caselle, pensate per un A4 intero, si scalano come l'inchiostro (×0,74).

---

## 4. Solo i blocchi di Tratto (`PianoBlocchi`)

Il documento è condiviso: Tratto deve ritrovare i suoi blocchi in mezzo al resto e cambiare solo quelli.

**Come si ritrovano**

- **Figura**: dall'id della sua immagine, salvato nel collegamento. Se qualcuno ha scritto nello stesso paragrafo (una didascalia), il paragrafo non è più solo di Tratto. Da quel momento si cambia o si toglie solo l'immagine, e la didascalia resta.
- **Testo**: da un **intervallo con nome** (named range) `tratto/<chiave>` che copre i suoi paragrafi. Google lo tiene attaccato al testo mentre il documento cambia. L'intervallo deve cominciare all'inizio di un paragrafo. Sono di Tratto solo i paragrafi che aveva scritto (quanti erano), riconosciuti da un'impronta del loro testo.
  - La documentazione di Google non dice se un intervallo si allarga quando qualcuno scrive proprio sul suo bordo. Se si è allargato, i paragrafi di Tratto sono quelli che somigliano di più a quelli scritti.
  - Nei test il documento finto prova tutti e quattro i modi (inizio e fine che si allargano o no).
- **Titolo**: è un testo come gli altri, con l'intervallo `tratto/titolo`.
- **Blocco non ritrovato** (cancellato a mano): si rimette.

**Cosa cambia**

- I blocchi ritrovati nello stesso ordine della nota **restano dove sono** (sottosequenza comune più lunga). Gli altri si tolgono e si rimettono.
- Un blocco nuovo va **subito dopo il blocco di Tratto che lo precede nella nota**, o in cima al documento. Il testo scritto a mano dopo una riga resta dopo quella riga, anche se sul tablet se ne aggiungono altre sotto.
- Un testo di Tratto cambiato a mano in Google Docs resta com'è finché la casella non cambia sul tablet; allora vince il tablet.
- Una figura cambiata si toglie e si rimette nello stesso punto, con la misura nuova. Un disegno rifatto identico (un tratto aggiunto e annullato) non si ricarica.

**Le richieste, in un solo `documents.batchUpdate`**

1. Si tolgono tutti gli intervalli `tratto/…`.
2. Si fanno i cambi del contenuto, **dal fondo verso l'inizio**: ogni cambio sposta solo indici già sistemati.
   - Un blocco nuovo si aggiunge prima dell'a capo del paragrafo che lo precede (a capo + blocco). Così funziona anche se dopo c'è una tabella o la fine del corpo.
   - Non si cancellano mai l'ultimo a capo del corpo né quello prima di una tabella: resta un paragrafo vuoto, che torna normale.
3. Con gli **indici finali** si rifanno gli intervalli, esatti, e si danno gli stili dei paragrafi e del testo. Si ridà il suo stile anche al paragrafo che segue un blocco tolto, perché una cancellazione che unisce due paragrafi può passargli quello del paragrafo tolto.
4. Per ultimi, dal fondo, gli elenchi: `createParagraphBullets` toglie i tab che danno il livello e sposta gli indici.

Tutto va con `writeControl.requiredRevisionId`: Google applica le richieste solo se il documento è ancora quello letto, perché gli indici sono calcolati su quello. Se qualcuno lo ha cambiato nel frattempo, si rilegge e si rifà il piano (fino a 3 volte). Dalla risposta si prendono gli id delle immagini inserite e degli intervalli creati.

Una nota lunga appena collegata entra a **gruppi di 20 figure** per `batchUpdate`. Le figure che restano fuori aspettano il giro dopo: quelle già nel documento restano come sono, quelle nuove per ora non entrano. Dopo ogni gruppo il collegamento si salva: se il lavoro si interrompe, riprende da lì.

Gli errori 401, 403 per troppe richieste, 429 e 5xx li gestisce `DriveRest` come per il backup, con un token nuovo e attese crescenti.

---

## 5. Le immagini e il link pubblico temporaneo

L'API di Docs **non accetta immagini private**: `insertInlineImage` vuole un indirizzo che Google possa scaricare senza autenticazione. Google scarica l'immagine una sola volta e ne tiene una copia dentro il documento. Per questo ogni immagine nuova fa questo giro:

1. il PNG si carica su Drive nella cartella **`Tratto/.immagini-docs`** (`appProperties {"tratto":"docs-immagini"}`), con `files.create` multipart. Si caricano 4 immagini alla volta;
2. si dà il permesso **«chiunque abbia il link: lettore»** (`permissions.create`, `{"role":"reader","type":"anyone"}`);
3. si manda `batchUpdate` con l'indirizzo dell'immagine;
4. subito dopo, anche se qualcosa è andato storto, si **toglie il permesso** (`permissions.delete`) e si **cancella il file** (`files.delete`, definitivo, senza cestino).

Il file è pubblico solo per quei pochi secondi, ed è raggiungibile solo da chi conosce il suo id (33 caratteri casuali). Se l'app muore a metà, al primo aggiornamento successivo (e dopo ogni errore) Tratto cancella tutto quello che è rimasto in `.immagini-docs`.

Indirizzi usati, in quest'ordine:

1. `https://lh3.googleusercontent.com/d/<id>`;
2. `https://drive.google.com/uc?export=download&id=<id>`, se Google rifiuta il primo.

Il primo risponde subito con l'immagine (200, `image/png`, nessun redirect), anche senza cookie. Il secondo passa per un redirect a `drive.usercontent.google.com`. Stando alle segnalazioni degli ultimi anni, Docs a volte lo rifiuta con «Access to the provided image was forbidden».

Con un account Google Workspace che vieta la condivisione fuori dall'organizzazione il permesso «chiunque» non si può dare: il pallino diventa rosso con il messaggio di Google.

---

## 6. Dati salvati

`files/google-docs/collegamenti.json` sta fuori da `files/tratto`. È lo stato della sincronizzazione di questo tablet, come `files/backup/drive.json`: non finisce nei backup e non viene sostituito da un ripristino. Il modello delle note (`nota.json`, `indice.json`) non cambia.

```json
{"versione": 2, "cartella": "<id di Tratto>", "immagini": "<id di .immagini-docs>",
 "note": {"<notaId>": {"documento": "<id del documento>",
   "firme": {"<paginaId>": "…"},
   "blocchi": [
     {"chiave": "titolo", "figura": false, "impronta": "…", "aspetto": "0,0", "intervallo": "kix.…", "paragrafi": ["…"]},
     {"chiave": "<paginaId>/t123", "figura": true, "pagina": "…", "impronta": "…", "aspetto": "6,44",
      "oggetto": "kix.…", "png": "…", "larghezza": 220.0, "altezza": 22.5}]}}}
```

- `impronta`: SHA-256 dei dati del blocco (tratti, immagini, testo). Finché non cambia, il blocco nel documento è giusto.
- `aspetto`: spazio sopra e rientro, in punti.
- `firme`: per ogni pagina, data e dimensione dei suoi file quando i suoi blocchi sono entrati nel documento. Finché non cambiano, la pagina non si rilegge nemmeno.
- `paragrafi`: impronta del testo di ogni paragrafo scritto da Tratto, per capire se è stato cambiato a mano.

Su Drive il documento ha `appProperties {"tratto":"docs","docsNota":"<notaId>"}`. Non ha `notaId`, che è la proprietà con cui il backup riconosce i suoi file `.tratto`: così backup e ripristino da Drive lo ignorano.

Un file della versione 1 (una immagine per pagina) si legge con le note segnate `vecchio`: il loro documento era tutto di Tratto, quindi al primo aggiornamento si svuota e si rifà a blocchi.

Se una nota collegata viene cancellata, il collegamento si toglie al primo aggiornamento; il documento resta.

---

## 7. Google Cloud Console

Oltre a quanto descritto in [backup.md](backup.md) §5:

- in **API e servizi → Libreria** cerca **Google Docs API** e premi **Abilita**, nello stesso progetto «Tratto»;
- non serve nessuno scope nuovo: `documents.batchUpdate` e `documents.get` accettano anche `https://www.googleapis.com/auth/drive.file`, che basta per i documenti creati da Tratto. La schermata di consenso non cambia e non serve nessuna verifica di Google (`drive.file` non è sensibile, `documents` lo sarebbe).

| Errore mostrato | Causa |
|---|---|
| «L'API di Google Docs non è abilitata nel progetto Google Cloud di Tratto.» | Manca l'API di Docs. Il collegamento non riesce e il documento appena creato viene cancellato. |
| «Il documento Google collegato non c'è più…» | Il documento è stato cancellato da Drive: scollegare e ricollegare la nota. |
| «Google Docs non è riuscito a scaricare le immagini: si riproverà.» | Google non ha potuto scaricare le immagini temporanee, neanche riprovando. Di solito passa da solo. |

---

## 8. Limiti

- **Non è in tempo reale al tratto**: il documento si aggiorna alle pause, un paio di secondi dopo l'ultimo tratto.
  - Una riga nuova costa un caricamento su Drive e un `batchUpdate`.
  - Le quote di Docs sono 60 scritture al minuto per utente: con un aggiornamento alla volta si resta sotto.
- **Il documento non torna sul tablet**: quello che si scrive in Google Docs resta nel documento, ma non entra nella nota.
- **Un tablet alla volta**: il collegamento è salvato solo su questo tablet. Su un tablet nuovo, o dopo aver reinstallato, la nota va collegata di nuovo e nasce un documento nuovo.
- **Nota rapida**: non si collega, perché non ha il menu dell'editor.
- Le caselle di testo non hanno una larghezza nel documento: il testo va fino al margine destro.
