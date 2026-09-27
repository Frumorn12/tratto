# Tratto

App di note a mano per tablet Android con penna (pensata per il Galaxy Tab S6 Lite con S Pen su LineageOS).

- Scrittura a bassa latenza con pressione e inclinazione della penna
- Penne, evidenziatore, gomma, lazo, pagine a righe/quadretti/puntini
- Annotazione di PDF ed esportazione in PDF
- Backup su Google Drive

Stato: in sviluppo.

## Compilare

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
