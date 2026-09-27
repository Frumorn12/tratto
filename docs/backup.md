# Backup di Tratto

Tratto ha due tipi di backup, tutti e due nel pacchetto `it.frumorn.tratto.backup`:

- il **file `.tratto`**, da salvare dove si vuole (Download, una chiavetta, un'altra app) o da condividere per una sola nota;
- il **backup su Google Drive**, incrementale e automatico una volta al giorno.

Hanno le stesse regole di ripristino, così un backup non cancella mai note che esistono solo sul tablet.

---

## 1. Il formato `.tratto`

Un file `.tratto` è uno zip:

```
manifest.json
tratto/indice.json
tratto/note/<id>/nota.json
tratto/note/<id>/pagine/<pagina>.tp
tratto/note/<id>/allegato.pdf        (se la nota viene da un PDF)
tratto/note/<id>/anteprima.webp
```

La cartella `tratto/` è la copia di `files/tratto` dell'app, con il formato descritto in `Archivio.kt`.

`manifest.json` è la prima voce dello zip:

```json
{"app": "Tratto", "formato": 1, "creato": 1790000000000, "note": 42, "dispositivo": "SM-P610"}
```

| Campo | Significato |
|---|---|
| `app` | sempre `"Tratto"`; un file senza questo valore non viene ripristinato |
| `formato` | versione del formato del backup (oggi 1); un formato più alto viene rifiutato con l'invito ad aggiornare l'app |
| `creato` | data del backup in millisecondi |
| `note` | numero di note contenute |
| `dispositivo` | modello del tablet che l'ha creato |

Cosa entra nello zip:

- l'indice, riscritto con le sole note che hanno davvero la loro cartella (`nota.json` presente);
- le cartelle di quelle note;
- con `esporta`, anche gli altri file che versioni future metteranno in `files/tratto`.

Cosa resta fuori:

- i file `*.tmp`, cioè salvataggi a metà;
- le cartelle di note che l'indice non cita, avanzi di cancellazioni.

Scelte sulla compressione:

- PDF e immagini sono già compressi e vanno nello zip senza ricompressione;
- tutto il resto è compresso con DEFLATE.

`esportaNota` produce lo stesso formato con una sola nota. L'indice contiene solo quella nota e la sua cartella, se ne ha una. Lo stesso file serve per condividere una nota e come unità del backup su Drive.

Si controllano anche le versioni interne: `versione` di `indice.json` (oggi 1) e `versione` di ogni `nota.json` (oggi 1). Se sono più alte, il backup viene rifiutato.

---

## 2. Ripristino

`BackupLocale.ripristina(archivio, input, modo)` lavora in tre fasi.

1. **Estrazione e controllo**
   - Lo zip viene estratto in `cacheDir/ripristino-*`.
   - Si estraggono solo `manifest.json` e `tratto/**`, e si ignorano i `.tmp`.
   - Protezione zip-slip:
     - vengono rifiutati i nomi con `..`, quelli assoluti e quelli con `\`;
     - ogni percorso viene poi confrontato, in forma canonica, con la cartella di destinazione.
   - Sono controllati il manifest (app e formato), l'indice e i `nota.json`.
   - Una nota elencata nell'indice ma senza `nota.json` leggibile, o con un id non valido, viene saltata e contata in `saltate`.
   - Se qualcosa non va viene lanciata `BackupNonValido`. Il suo messaggio in italiano è pronto da mostrare, e l'archivio non viene toccato.

2. **Preparazione**
   - Il nuovo archivio completo viene costruito in `files/tratto.preparazione`.
   - Le note locali vi arrivano con hard link, quindi senza copiare dati. Se il filesystem non li permette, vengono copiate.
   - Gli hard link sono sicuri perché Archivio non modifica mai un file sul posto: scrive un `.tmp` e poi lo rinomina.

3. **Scambio**
   - Tenendo il lock dell'archivio si fanno tre rename:
     - `tratto.preparazione` diventa `tratto.nuovo`;
     - `tratto` diventa `tratto.vecchia`;
     - `tratto.nuovo` diventa `tratto`.
   - Poi viene chiamato `archivio.ricarica()`.
   - Se l'app muore tra gli ultimi due rename, al prossimo avvio `Archivio.caricaIndice()` trova `tratto.nuovo` e lo rimette al suo posto.
   - Un errore in qualunque punto prima dello scambio lascia l'archivio com'era.
   - In modalità UNISCI, se durante la preparazione qualcuno ha salvato una nota (l'indice su disco è cambiato), il ripristino si ferma senza modifiche e chiede di riprovare.
   - Conviene comunque chiudere l'editor prima di ripristinare.

Il risultato è un `RiepilogoRipristino(aggiunte, aggiornate, copie, cartelleAggiunte, saltate)`.

### Regole di UNISCI

Le stesse regole valgono per il ripristino da Drive.

| Situazione | Risultato |
|---|---|
| Nota solo sul tablet | Resta com'è. |
| Nota solo nel backup | Viene aggiunta (`aggiunte`). |
| Nota su entrambi, **stesse pagine** (stessi `nota.json`, `.tp` e PDF; l'anteprima non conta) | Nessuna copia. Titolo, cartella e preferita si prendono dalla versione con `modificata` più recente (`aggiornate` se è quella del backup). |
| Nota su entrambi, pagine diverse, **backup più recente** | La versione del backup prende l'id (`aggiornate`). Quella del tablet resta come nuova nota con un id nuovo e titolo "<titolo> (copia)" (`copie`). |
| Nota su entrambi, pagine diverse, **tablet più recente o stessa data** | Resta quella del tablet. Quella del backup diventa "<titolo> (copia)" (`copie`), a meno che la stessa copia esista già (stesso titolo e stesse pagine: ripristinare due volte lo stesso backup non crea doppioni). |
| Cartella solo nel backup | Viene aggiunta (`cartelleAggiunte`). Se sul tablet esiste una cartella con lo stesso nome (senza distinguere maiuscole e spazi), si usa quella. |
| Cartella su entrambi (stesso id) | Resta quella del tablet. |

Nessuna nota viene mai cancellata da UNISCI. Una nota del backup che punta a una cartella inesistente finisce fuori dalle cartelle.

### SOSTITUISCI

L'archivio del tablet viene sostituito per intero da quello del backup. Le note che esistono solo sul tablet si perdono: la UI deve chiedere conferma. `aggiunte` è il numero di note ripristinate.

---

## 3. Backup su Google Drive

### Layout su Drive

Scope `drive.file`: Tratto vede solo i file che ha creato lui. I file sono comunque visibili e scaricabili dall'utente nel suo Drive.

```
Tratto/                         cartella, appProperties {"tratto":"cartella"}
  <titolo>.tratto               una nota (formato di esportaNota),
                                appProperties {"tratto":"nota","notaId":"<id>","modificata":"<ms>"}
  indice.tratto-indice          indice.json del tablet (JSON), appProperties {"tratto":"indice"}
```

Il nome del file è il titolo della nota, oppure l'id se il titolo è vuoto. Più note possono avere lo stesso nome: Tratto le distingue da `notaId`.

### Backup (`DriveBackup.sincronizza`)

1. Il token si ottiene senza interfaccia con `AuthorizationClient.authorize()`.
   - Se Google chiede di nuovo il consenso, viene lanciata `AccessoNecessario` e lo stato diventa `serveAccesso = true`.
   - Su HTTP 401 il token viene cancellato (`clearToken`) e si riprova una volta.
2. Si cerca la cartella "Tratto" per `appProperties` e, se manca, la si crea. Poi si elencano i suoi file.
3. Per ogni nota dell'indice, confrontandola con lo stato locale in `files/backup/drive.json` (nota → id del file su Drive, `modificata` caricata, nome):
   - se `modificata` è cambiata, o se il file su Drive non c'è più, si carica lo zip della nota;
   - se il file esiste già si fa un aggiornamento (PATCH), altrimenti lo si crea;
   - fino a 5 MB il caricamento è multipart; oltre è resumable a blocchi da 8 MB, e se la rete cade riprende dal punto in cui Drive si è fermato;
   - se è cambiato solo il titolo, si rinomina il file senza ricaricarlo;
   - se il file su Drive ha già la stessa `modificata` (per esempio dopo un ripristino da Drive), lo si adotta senza ricaricarlo.
4. Le note cancellate sul tablet hanno il loro file spostato nel **cestino di Drive** (`trashed=true`), che lo tiene per 30 giorni.
   - Con l'indice locale vuoto non si cestina nulla, per prudenza.
5. L'indice viene caricato quando il suo SHA-256 cambia. Contiene le cartelle, anche quelle vuote, e titolo, cartella e preferita delle note, che possono cambiare senza toccare le pagine.

Se l'account Google cambia, lo stato viene azzerato e tutto viene ricaricato nella cartella del nuovo account.

### Ripristino da Drive (`DriveBackup.ripristinaDaDrive`)

1. Si scaricano l'indice e tutte le note della cartella. Se ci sono più file con lo stesso `notaId`, si prende quello con `modificata` più recente.
2. Ogni zip viene controllato come un `.tratto` qualsiasi; i file non validi vengono saltati e contati in `saltate`.
3. Per ogni nota si usano titolo, cartella e preferita dell'indice su Drive, ma solo se lì ha la stessa `modificata` dello zip (sono dati più recenti). Le cartelle sono l'unione di quelle dell'indice e di quelle degli zip.
4. Tutto viene unito al tablet con le regole di UNISCI, con lo stesso scambio atomico del §2.
5. Le note che dopo il ripristino coincidono con Drive vengono segnate come già caricate.

### In background (WorkManager)

- `BackupWorker` è un `CoroutineWorker`.
- `BackupWorker.pianifica(context)` gestisce il **backup giornaliero** (`PeriodicWorkRequest` ogni 24 ore):
  - vincoli: rete non a consumo (se `soloWifi`) oppure qualsiasi, batteria non scarica, e tablet in carica se `soloInCarica`;
  - viene cancellato se il backup automatico è spento o se non c'è un account collegato;
  - viene richiamato da `ImpostazioniBackup.imposta*`, dopo il collegamento e dopo lo scollegamento.
- `BackupWorker.backupOra(context)` avvia il **backup subito**: lavoro expedited, con qualsiasi rete.
- Errori temporanei (rete, 5xx, 429): `Result.retry()` fino a 5 tentativi.
- `AccessoNecessario` o errori definitivi: `Result.failure()`. Il messaggio resta in `StatoBackup.errore`.
- WorkManager **non** si inizializza all'avvio:
  - `WorkManagerInitializer` è tolto dal manifest con `tools:node="remove"`;
  - `TrattoApp` è un `Configuration.Provider`, quindi l'inizializzazione avviene al primo `WorkManager.getInstance`.

### API per la UI

```kotlin
// File locale (fuori dal main thread)
BackupLocale.esporta(archivio, outputStream)
BackupLocale.esportaNota(archivio, notaId, outputStream)
BackupLocale.ripristina(archivio, inputStream, ModoRipristino.UNISCI) // -> RiepilogoRipristino, oppure BackupNonValido
BackupLocale.nomeFile()   // "Tratto 2026-09-27 15.30.tratto"
BackupLocale.MIME         // per ACTION_CREATE_DOCUMENT

// Drive
val drive = DriveBackup(context)
drive.autorizza()                    // Risultato.Autorizzato(token) | Risultato.ServeConsenso(pendingIntent)
drive.completaAutorizzazione(intent) // col risultato di StartIntentSenderForResult
drive.sincronizza()                  // -> RiepilogoBackup(caricate, rinominate, cestinate)
drive.ripristinaDaDrive()            // -> RiepilogoRipristino
drive.disconnetti()
val collega = rememberAccessoDrive { esito -> /* snackbar */ } // helper Compose per il pulsante "Collega"

// Stato e impostazioni
ImpostazioniBackup.stato(context)    // StateFlow<StatoBackup>
ImpostazioniBackup.impostaAutomatico(context, true)
ImpostazioniBackup.impostaSoloWifi(context, true)
ImpostazioniBackup.impostaSoloInCarica(context, false)
BackupWorker.backupOra(context)
```

`StatoBackup` ha questi campi: `ultimoBackup`, `account`, `automatico`, `soloWifi`, `inCorso`, `errore`, `soloInCarica` e `serveAccesso`.

---

## 4. Da fare una volta in Google Cloud Console

Nel codice non c'è nessun client ID e non serve `google-services.json`. Play services riconosce l'app dalla coppia **package + SHA-1** del certificato di firma, quindi basta registrarla in un progetto Cloud.

1. Vai su <https://console.cloud.google.com/>, crea un progetto nuovo e chiamalo **Tratto**.
2. **API e servizi → Libreria**: cerca **Google Drive API** e premi **Abilita**.
3. **Google Auth Platform → Branding** (se è la prima volta: "Inizia"):
   - nome dell'app: `Tratto`;
   - email di assistenza utenti: la tua;
   - dati di contatto dello sviluppatore: la tua email.
   Il logo lascialo vuoto, perché caricarne uno fa scattare la verifica del brand.
4. **Google Auth Platform → Audience (Pubblico)**:
   - tipo di utente: **Esterno**;
   - poi premi **Pubblica app** e conferma, così lo stato passa a **In produzione**.
     - In stato "Test" servono i test user, e il consenso **scade dopo 7 giorni**: il backup automatico si fermerebbe ogni settimana.
     - Con il solo scope `drive.file`, che non è sensibile, Google non chiede nessuna verifica.
5. **Google Auth Platform → Accesso ai dati (Data Access)**:
   - premi **Aggiungi o rimuovi ambiti**;
   - aggiungi `https://www.googleapis.com/auth/drive.file` ("See, edit, create and delete only the specific Google Drive files you use with this app");
   - salva.
6. **Google Auth Platform → Client → Crea client**, per la chiave di debug:
   - tipo di applicazione: **Android**;
   - nome: `Tratto debug`;
   - nome del pacchetto: `it.frumorn.tratto`;
   - impronta del certificato SHA-1 della chiave di debug (`~/.android/debug.keystore`):

     ```
     EA:77:4E:74:A3:A8:F8:BB:20:99:27:4B:ED:D3:42:BB:5F:C3:51:27
     ```

     L'impronta è stata letta con:

     ```sh
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
     ```

     Oggi anche la build release è firmata con la chiave di debug (`signingConfig = debug` in `app/build.gradle.kts`), quindi questo client basta per entrambe.
7. **Client per la chiave di release**, quando esisterà:
   - crea un secondo client **Android** con lo stesso package e lo SHA-1 della nuova chiave:

     ```sh
     keytool -list -v -keystore tratto-release.jks -alias tratto
     ```

   - serve un client per ogni SHA-1, e possono convivere.
8. Aspetta qualche minuto, perché le modifiche impiegano un po' a propagarsi. Poi, dall'app, "Collega Google Drive".

Se qualcosa non va:

| Errore mostrato | Causa |
|---|---|
| "Accesso a Google non configurato per questa app…" (DEVELOPER_ERROR, codice 10) | Package o SHA-1 non registrati, oppure registrati da pochi minuti. Controlla il client Android del punto 6. |
| "L'API di Google Drive non è abilitata…" | Manca il punto 2. |
| Il consenso scade dopo una settimana | L'app è ancora in stato "Test" (punto 4). |

---

## 5. Limiti noti

- **Un solo tablet alla volta**.
  - Il backup su Drive è pensato per un dispositivo. Se due tablet caricano la stessa nota, vince l'ultimo che carica.
  - La versione precedente resta nella cronologia delle revisioni di Drive (30 giorni) e non nell'app.
  - Il ripristino da Drive, invece, non perde nulla (regole UNISCI).
- **Una nota è uno zip unico**. Se cambiano le pagine di una nota con un PDF grande, il PDF viene ricaricato. I PDF non sono ancora blob separati e deduplicati come proposto nella ricerca (§6.6).
- **Durata dei lavori in background**. WorkManager ferma un lavoro dopo circa 10 minuti. Il progresso si salva nota per nota, quindi il giro successivo riprende da dove era arrivato. Una singola nota enorme su una rete molto lenta, però, potrebbe non finire mai: servirebbe un foreground service `dataSync`.
- **Primo backup da un'installazione nuova**. Se si attiva il backup su un tablet appena installato senza prima fare "Ripristina da Drive", l'indice su Drive viene sostituito da quello locale. Le note su Drive non si toccano, perché Tratto cestina solo file che ha caricato lui. Si perdono solo le cartelle vuote del vecchio indice.
- **Editor aperto durante il ripristino**. Se una pagina viene salvata proprio mentre si ripristina, il ripristino si ferma e chiede di riprovare (UNISCI) oppure la modifica si perde (SOSTITUISCI). Meglio chiudere l'editor prima.
- **Spazio**. Il ripristino estrae tutto il backup in cache prima di applicarlo, quindi serve almeno lo spazio del backup, più 32 MB di margine.
