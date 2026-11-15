#!/usr/bin/env bash
# Compile le debug « completa », démarre l'émulateur tablette s'il ne tourne pas,
# installe l'APK et ouvre Tratto.
set -euo pipefail
cd "$(dirname "$0")/.."

AVD="Tratto_Tablet"
ACTIVITE="it.frumorn.tratto/.MainActivity"
APK="app/build/outputs/apk/completa/debug/app-completa-debug.apk"

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
  else
    ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
export ANDROID_HOME

if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 introuvable. Installe-le avec : brew install --cask temurin@21" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

if [[ ! -x "$ANDROID_HOME/emulator/emulator" ]]; then
  echo "Émulateur introuvable dans $ANDROID_HOME/emulator." >&2
  exit 1
fi
if ! emulator -list-avds | grep -qx "$AVD"; then
  echo "L'AVD $AVD n'existe pas. Crée-le avant de relancer ce script." >&2
  exit 1
fi

echo "Compilation..."
./gradlew :app:assembleCompletaDebug

if [[ ! -f "$APK" ]]; then
  echo "APK introuvable : $APK" >&2
  exit 1
fi

emulateur_lance() {
  pgrep -f "[e]mulator.*-avd ${AVD}" >/dev/null 2>&1 \
    || pgrep -f "qemu-system.*-avd ${AVD}" >/dev/null 2>&1
}

if emulateur_lance; then
  echo "Émulateur $AVD déjà lancé."
else
  echo "Démarrage de l'émulateur $AVD..."
  log="$HOME/.android/avd/${AVD}.avd/launch.log"
  nohup emulator -avd "$AVD" >"$log" 2>&1 &
  pid=$!
  sleep 3
  if ! kill -0 "$pid" 2>/dev/null && ! emulateur_lance; then
    echo "L'émulateur s'est arrêté. Dernières lignes de $log :" >&2
    tail -n 30 "$log" >&2 || true
    exit 1
  fi
fi

echo "Attente d'adb..."
adb wait-for-device

serial=""
for _ in $(seq 1 60); do
  while read -r candidat; do
    nom="$(adb -s "$candidat" emu avd name 2>/dev/null | tr -d '\r' | head -n 1 || true)"
    if [[ "$nom" == "$AVD" ]]; then
      serial="$candidat"
      break
    fi
  done < <(adb devices | awk '/^emulator-/{print $1}')
  [[ -n "$serial" ]] && break
  sleep 2
done

if [[ -z "$serial" ]]; then
  echo "Aucun émulateur $AVD visible par adb." >&2
  exit 1
fi

echo "Attente du démarrage Android..."
boot=""
for _ in $(seq 1 90); do
  boot="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$boot" == "1" ]] && break
  sleep 2
done
if [[ "$boot" != "1" ]]; then
  echo "L'émulateur n'a pas fini de démarrer." >&2
  exit 1
fi

echo "Installation..."
adb -s "$serial" install -r "$APK"
adb -s "$serial" shell am start -n "$ACTIVITE"
echo "Tratto est lancé sur $AVD ($serial)."
