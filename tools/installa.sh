#!/usr/bin/env bash
# Compila la versione ottimizzata, la installa sul tablet e la precompila tutta (avvio piu' rapido).
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
./gradlew :app:assembleRelease -q
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f it.frumorn.tratto
echo "Tratto installato e precompilato."
