#!/usr/bin/env bash
# Compila la versione ottimizzata (flavor "completa", con ML Kit e Google Drive), la installa sul
# tablet e la precompila tutta (avvio piu' rapido).
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:assembleCompletaRelease -q
adb install -r app/build/outputs/apk/completa/release/app-completa-release.apk
adb shell cmd package compile -m speed -f it.frumorn.tratto
echo "Tratto installato e precompilato."
