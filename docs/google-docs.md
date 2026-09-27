# Nota in Google Docs, dal vivo

Una nota di Tratto si può collegare a un documento Google Docs. Chi apre il documento su un computer lo vede aggiornarsi mentre si scrive sul tablet: il titolo della nota in cima, poi ogni pagina come immagine, in ordine.

C'è solo nella versione **completa** (serve l'accesso all'account Google di Play services). Nella versione **libera** le voci non compaiono.

Il collegamento va in una sola direzione, **da Tratto a Google Docs**. Il documento è uno specchio: quello che si scrive direttamente nel documento viene tolto al prossimo aggiornamento.

Il codice è nel pacchetto `it.frumorn.tratto.googledocs` di `src/completa`.

---

## 1. Come si usa

Nel menu ⋮ dell'editor, sezione **Google Docs**:

- **Collega a Google Docs**
  - usa lo stesso accesso a Google del backup su Drive (scope `drive.file`); se l'accesso non c'è ancora, compare la schermata di consenso di Google, la stessa di «Collega Google Drive» nelle impostazioni;
  - salva la nota, crea il documento nella cartella «Tratto» di Drive, con il titolo della nota e le pagine A4, e lo riempie subito.
- **Apri in Google Docs**: apre `https://docs.google.com/document/d/<id>/edit` (app Google Docs o browser). Da lì si condivide il documento con chi deve guardarlo.
- **Scollega da Google Docs**: la nota smette di aggiornare il documento. Il documento resta su Drive com'era.

Accanto al titolo, solo per le note collegate, c'è un piccolo **«Docs»** con un pallino:

| Pallino | Significato |
|---|---|
| grigio | collegata, niente di nuovo da quando l'app è aperta |
| viola chiaro | ci sono modifiche, partono tra pochi secondi |
| viola che pulsa | aggiornamento in corso |
| verde | documento aggiornato |
| ambra | non è andata per un problema passeggero (rete, Google occupato): si riprova da soli, anche ad app chiusa |
| rosso | errore; se Google vuole di nuovo il consenso, toccando si ricollega |

Un tocco sul pallino apre il documento; con ambra o rosso mostra il motivo e riprova.

Collegare una nota collega anche Google Drive nelle impostazioni (stesso account, stesso permesso), senza accendere il backup automatico. Se si scollega Google Drive dalle impostazioni, l'accesso viene revocato e il pallino diventa rosso finché non si ricollega.

---

## 2. Quando si aggiorna

- Dopo ogni salvataggio dell'editor (un secondo dopo l'ultimo tratto) e dopo ogni cambio di titolo, `GoogleDocs.modificata` fa partire l'aggiornamento **4 secondi dopo l'ultima modifica**. Mentre si scrive senza pause non parte niente; alla prima pausa il documento si aggiorna.
- Un aggiornamento già partito non si interrompe. Se nel frattempo arrivano altre modifiche, ne parte un altro subito dopo.
- Alla **chiusura dell'editor** e quando l'app va **in secondo piano**, se ci sono modifiche non ancora mandate, `GoogleDocs.finisciInBackground` le affida a `DocsWorker` (WorkManager, un lavoro per nota, rete richiesta). Il lavoro finisce anche ad app chiusa e, senza rete, parte appena la rete torna.
- Senza rete anche l'aggiornamento dall'app passa a `DocsWorker`.
- Un aggiornamento alla volta, per tutte le note (un `Mutex`).

---

## 3. Com'è fatto il documento

```
Titolo della nota               paragrafo con stile TITLE
[immagine della pagina 1]       paragrafo centrato, larga 440 pt
(testo della pagina 1)          paragrafi normali, per il testo battuto (vedi §6)
[immagine della pagina 2]
...
```

- Pagina A4 verticale, impostata alla creazione (`updateDocumentStyle`).
- Ogni immagine è larga 440 punti, così sta nei margini normali di un A4. Una pagina di Tratto alta al massimo 690 punti occupa una pagina del documento: si scorrono come sul tablet. Le pagine molto alte (PDF) si rimpiccioliscono per starci.
- Le immagini sono PNG larghi 1600 pixel, circa 260 dpi, disegnati come nell'esportazione (`EsportaPdf.immaginePagina`: sfondo, pagina del PDF, tratti con Ink).
- Il nome del file su Drive segue il titolo della nota.

---

## 4. Aggiornamento del documento (`SincroniaDocs`)

`SincroniaDocs` lavora con due interfacce: `ServizioDocs` per Google (`ServizioGoogle`: Docs e Drive con `DriveRest`) e `NotaDocs` per la nota (`NotaArchivio`). Nei test al loro posto ci sono un Google finto, con un documento che applica le regole di `batchUpdate` sugli indici, e una nota finta.

1. Si legge il documento con `documents.get`, chiedendo solo i campi che servono (`LetturaDoc.CAMPI`): indici, stile e contenuto dei paragrafi, id delle immagini.
2. Ogni paragrafo diventa una chiave: testo con il suo stile, immagine di una pagina (riconosciuta dall'id dell'immagine salvato nel collegamento), oppure «sconosciuto» (testo scritto a mano nel documento, immagini non di Tratto, tabelle...).
3. `PianoDocs` confronta le chiavi del documento con quelle della nota (sottosequenza comune più lunga):
   - i paragrafi giusti **restano dove sono**, con le loro immagini: chi guarda non vede sparire e ricomparire le pagine;
   - gli altri si cancellano e quelli che mancano si inseriscono;
   - le richieste si calcolano **dal fondo verso l'inizio**, così una modifica sposta solo indici già sistemati;
   - l'ultimo a capo del corpo non si può cancellare: in fondo si cancella l'a capo del paragrafo prima e se ne riprende lo stile;
   - se non resta niente, si svuota il corpo e si riscrive tutto.
4. Pagine da ridisegnare: quelle da inserire e quelle rimaste i cui dati sono cambiati.
   - Per ogni pagina il collegamento ricorda l'**impronta** dei dati disegnati (SHA-256 di pagina, tratti e testo, `ImprontaPagina`) e lo **SHA-256 del PNG** caricato.
   - Una **firma** economica (data e dimensione del file dei tratti, pagina, testo) evita perfino di rileggere i tratti delle pagine che non sono cambiate. Si prende prima di leggere i tratti: se il file cambia intanto, la volta dopo non coincide.
   - Una pagina ridisegnata identica (per esempio un tratto aggiunto e poi annullato) non si ricarica.
5. Le immagini nuove si caricano (§5) e tutte le modifiche vanno in un solo `documents.batchUpdate`, con `writeControl.requiredRevisionId`: Google le applica solo se il documento è ancora quello letto, perché gli indici sono calcolati su quello. Le pagine rimaste ma ridisegnate si aggiornano con `replaceImage` sull'id dell'immagine (stessa dimensione, il resto non si muove).
6. Si rilegge il documento e si prendono gli id delle immagini nell'ordine delle pagine, da salvare nel collegamento.
7. Il nome del file su Drive si aggiorna se il titolo è cambiato (`files.update`).

Una nota lunga appena collegata entra a **gruppi di 10 immagini** per `batchUpdate`: dopo ogni gruppo il collegamento si salva, e se il lavoro si interrompe riprende da lì.

Se `batchUpdate` risponde 400:

- se Google non è riuscito a scaricare un'immagine, si aspetta un attimo e si riprova con l'altro indirizzo (§5), fino a 3 volte; poi l'aggiornamento si rimanda;
- altrimenti (documento cambiato nel frattempo, richieste rifiutate) si rilegge il documento; se le richieste vengono rifiutate di nuovo, si svuota il corpo e si riscrive tutto.

Gli errori 401, 403 per troppe richieste, 429 e 5xx li gestisce `DriveRest` come per il backup: token nuovo e attese crescenti.

---

## 5. Le immagini e il link pubblico temporaneo

L'API di Docs **non accetta immagini private**: `insertInlineImage` e `replaceImage` vogliono un indirizzo che Google possa scaricare senza autenticazione. Google scarica l'immagine una sola volta e ne tiene una copia dentro il documento. Per questo ogni immagine nuova fa questo giro:

1. il PNG si carica su Drive nella cartella **`Tratto/.immagini-docs`** (`appProperties {"tratto":"docs-immagini"}`), con `files.create` multipart;
2. si dà il permesso **«chiunque abbia il link: lettore»** (`permissions.create`, `{"role":"reader","type":"anyone"}`);
3. si manda `batchUpdate` con l'indirizzo dell'immagine;
4. subito dopo, anche se qualcosa è andato storto, si **toglie il permesso** (`permissions.delete`) e si **cancella il file** (`files.delete`, definitivo, senza cestino).

Il file è pubblico solo per quei pochi secondi, ed è raggiungibile solo da chi conosce il suo id (33 caratteri casuali). Se l'app muore a metà, al primo aggiornamento successivo (e dopo ogni errore) Tratto cancella tutto quello che è rimasto in `.immagini-docs`.

Indirizzo usato, in quest'ordine:

1. `https://lh3.googleusercontent.com/d/<id>`
2. `https://drive.google.com/uc?export=download&id=<id>` (se Google rifiuta il primo)

Il primo risponde subito con l'immagine (200, `image/png`, nessun redirect), anche senza cookie. Il secondo è il `webContentLink` di Drive: passa per un redirect a `drive.usercontent.google.com` e, stando alle segnalazioni degli ultimi anni, Docs a volte lo rifiuta con «Access to the provided image was forbidden». Controllato con curl a settembre 2026 su un file pubblico: tutti e due restituiscono i byte originali del PNG.

Con un account Google Workspace che vieta la condivisione fuori dall'organizzazione il permesso «chiunque» non si può dare: il pallino diventa rosso con il messaggio di Google.

---

## 6. Testo battuto (da fare)

Il piano supporta già paragrafi di testo vero sotto l'immagine di ogni pagina (`Voluto.Testo`), provati nei test. Manca solo da dove prenderli: `NotaArchivio` ha il parametro `testoPagina: (notaId, pagina) -> List<String>`, oggi sempre vuoto. Quando le pagine avranno le caselle di testo, basta passare la funzione che restituisce il loro testo semplice (un elemento per paragrafo; gli a capo dentro un elemento diventano paragrafi) dove `GoogleDocs.esegui` crea `NotaArchivio`. Il testo entra anche nell'impronta, così l'immagine si rifà se il testo è disegnato anche lì. Per metterlo sopra l'immagine invece che sotto basta cambiare l'ordine in `SincroniaDocs.sincronizza`.

---

## 7. Dati salvati

`files/google-docs/collegamenti.json`, fuori da `files/tratto`: è lo stato della sincronizzazione di questo tablet, come `files/backup/drive.json`, e non finisce nei backup né viene sostituito da un ripristino. Il modello delle note (`nota.json`, `indice.json`) non cambia.

```json
{"versione": 1, "cartella": "<id di Tratto>", "immagini": "<id di .immagini-docs>",
 "note": {"<notaId>": {"documento": "<id del documento>",
                        "pagine": {"<paginaId>": {"oggetto": "kix.…", "dimensione": "1000.0x1414.0",
                                                   "impronta": "…", "png": "…", "firma": "…"}}}}}
```

Su Drive il documento ha `appProperties {"tratto":"docs","docsNota":"<notaId>"}`. Non ha `notaId`: è la proprietà con cui il backup riconosce i suoi file `.tratto`, quindi backup e ripristino da Drive lo ignorano.

Se una nota collegata viene cancellata, il collegamento si toglie al primo aggiornamento; il documento resta.

---

## 8. Google Cloud Console

Oltre a quanto descritto in [backup.md](backup.md) §5:

- **API e servizi → Libreria**: cerca **Google Docs API** e premi **Abilita**, nello stesso progetto «Tratto».
- Nessuno scope nuovo: `documents.batchUpdate` e `documents.get` accettano anche `https://www.googleapis.com/auth/drive.file`, che basta per i documenti creati da Tratto. La schermata di consenso non cambia e non serve nessuna verifica di Google (`drive.file` non è sensibile; `documents` lo sarebbe).

| Errore mostrato | Causa |
|---|---|
| «L'API di Google Docs non è abilitata nel progetto Google Cloud di Tratto.» | Manca l'API di Docs. Il collegamento non riesce e il documento appena creato viene cancellato. |
| «Il documento Google collegato non c'è più…» | Il documento è stato cancellato da Drive: scollegare e ricollegare la nota. |
| «Google Docs non è riuscito a scaricare le immagini delle pagine: si riproverà.» | Google non ha potuto scaricare le immagini temporanee neanche riprovando: di solito passa da solo. |

---

## 9. Limiti

- **Una direzione sola**: il documento non si modifica a mano. Chi scrive nel documento perde le modifiche al prossimo aggiornamento.
- **Non è in tempo reale al tratto**: il documento si aggiorna alle pause (4 secondi), con 6–8 richieste per aggiornamento. Una pagina cambiata costa un PNG di qualche centinaio di KB.
- **Quote di Docs**: 60 scritture al minuto per utente; con le pause di 4 secondi si resta sotto.
- **Un tablet alla volta**: il collegamento è salvato solo su questo tablet. Su un tablet nuovo (o dopo aver reinstallato) la nota va collegata di nuovo, e nasce un documento nuovo.
- **Nota rapida**: non si collega (non ha il menu dell'editor).
