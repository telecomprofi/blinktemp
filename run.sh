#!/usr/bin/env bash
# Build, install and launch Blink Temp on a USB-connected phone (macOS / Linux).
set -e
cd "$(dirname "$0")"

# 1. Java: use JAVA_HOME if set, otherwise Android Studio's bundled JDK.
if [ -z "$JAVA_HOME" ]; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/android-studio/jbr" "/opt/android-studio/jbr" "/snap/android-studio/current/jbr"; do
    [ -x "$j/bin/java" ] && export JAVA_HOME="$j" && break
  done
fi
[ -z "$JAVA_HOME" ] && ! command -v java >/dev/null && { echo "No JDK found. Install Android Studio or set JAVA_HOME."; exit 1; }

# 2. Android SDK -> local.properties
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for s in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$s" ] && SDK="$s" && break; done
fi
[ -z "$SDK" ] && { echo "Android SDK not found. Open Android Studio once (it installs the SDK) or set ANDROID_HOME."; exit 1; }
echo "sdk.dir=$SDK" > local.properties
ADB="$SDK/platform-tools/adb"

# 3. Phone connected?
"$ADB" start-server >/dev/null
if ! "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  echo "No phone found. Enable USB debugging, plug in, and accept the 'Allow USB debugging?' prompt."
  "$ADB" devices; exit 1
fi

# 4. Build + install + launch
sh ./gradlew installDebug
"$ADB" shell am start -n com.example.blinktemp/.MainActivity
echo "Done. Sign in in the app, then add the 'Blink Temp' widget to your home screen."
