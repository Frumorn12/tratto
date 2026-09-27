# Tratto

App di note a mano per tablet Android con penna (pensata per il Galaxy Tab S6 Lite con S Pen su LineageOS).

- Scrittura a bassa latenza con pressione e inclinazione della penna
- Penne, evidenziatore, gomma, lazo, pagine a righe/quadretti/puntini
- Annotazione di PDF ed esportazione in PDF
- Backup su Google Drive

Stato: in sviluppo.

## Due versioni

- **completa** (predefinita): trascrizione della scrittura e calcoli automatici con ML Kit, backup su Google Drive.
- **libera**: solo software libero, per F-Droid. Niente ML Kit né Play services; il backup automatico va in una cartella scelta dall'utente (vedi `docs/backup.md`).

Il codice che cambia sta in `app/src/completa` e `app/src/libera`.

## Compilare

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleCompletaDebug    # oppure assembleLiberaDebug
adb install -r app/build/outputs/apk/completa/debug/app-completa-debug.apk
```
