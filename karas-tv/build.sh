#!/bin/bash
# Manual build for TV Launcher — no Gradle needed.
#
# Needs: JDK 17+, an Android SDK (platform android-34 + build-tools 34.x),
# the Kotlin compiler (kotlinc 2.x) and a debug keystore. Override any path
# with an environment variable, e.g.
#   SDK=~/Android/Sdk KOTLINC=~/kotlinc ./build.sh
#
# Writes karas-tv-debug.apk in this directory.
set -e
cd "$(dirname "$0")"

SDK="${SDK:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="${BT:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
# android-34 is what the app targets, but any newer platform compiles it too.
# Falling back to the newest one installed means a machine with only android-35
# or 36 builds instead of failing on a path that happens not to exist.
if [ -z "$PLATFORM" ]; then
  if [ -f "$SDK/platforms/android-34/android.jar" ]; then
    PLATFORM="$SDK/platforms/android-34/android.jar"
  else
    PLATFORM="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
  fi
fi
AAPT2="${AAPT2:-$BT/aapt2}"
D8="${D8:-$BT/d8}"
ZIPALIGN="${ZIPALIGN:-$BT/zipalign}"
APKSIGNER="${APKSIGNER:-$BT/apksigner}"
KOTLINC="${KOTLINC:-$HOME/kotlinc}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
PKG=com.moomoo.tvlauncherbase
VCODE=30
VNAME=2.9.11

# Say which piece is missing. Without this the build dies later on an opaque
# "No such file or directory" from aapt2 or kotlinc.
missing=""
[ -f "$PLATFORM" ] || missing="$missing\n  android.jar      (install an SDK platform, e.g. sdkmanager 'platforms;android-34'; SDK=$SDK)"
[ -x "$AAPT2" ]    || missing="$missing\n  aapt2            (install build-tools, e.g. sdkmanager 'build-tools;34.0.0')"
[ -x "$D8" ]       || missing="$missing\n  d8               (same build-tools package)"
[ -f "$KOTLINC/lib/kotlin-compiler.jar" ] || missing="$missing\n  kotlin compiler  (unzip kotlin-compiler-2.x.zip; KOTLINC=$KOTLINC)"
if [ -n "$missing" ]; then
  # shellcheck disable=SC2059
  printf "build.sh cannot find:$missing\n\nSet SDK, BT or KOTLINC to point at them, e.g.\n  SDK=~/Android/Sdk KOTLINC=~/kotlinc ./build.sh\n" >&2
  exit 1
fi

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

rm -rf out
mkdir -p out/compiled-res out/gen out/classes out/dex

# Gradle supplies the package name from build.gradle; the manual pipeline injects it.
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">#" \
  app/src/main/AndroidManifest.xml > out/AndroidManifest.xml

echo "== aapt2 =="
"$AAPT2" compile --dir app/src/main/res -o out/compiled-res/
"$AAPT2" link -o out/base.apk --manifest out/AndroidManifest.xml -I "$PLATFORM" \
  --java out/gen --min-sdk-version 26 --target-sdk-version 34 \
  --version-code $VCODE --version-name $VNAME out/compiled-res/*.flat

echo "== kotlinc =="
java -Xmx2g -cp "$KOTLINC/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -cp "$PLATFORM:$KOTLINC/lib/kotlin-stdlib.jar" -jvm-target 1.8 \
  -d out/classes $(find app/src/main/java -name '*.kt') $(find out/gen -name '*.java')

echo "== d8 (app + Kotlin stdlib — the stdlib MUST be dexed in, or the app crashes on launch) =="
(cd out/classes && jar cf ../app.jar .)
"$D8" --release --min-api 26 --lib "$PLATFORM" --output out/dex out/app.jar "$KOTLINC/lib/kotlin-stdlib.jar"

echo "== package + align + sign =="
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes*.dex)
"$ZIPALIGN" -f 4 out/unsigned.apk out/aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --out karas-tv-debug.apk out/aligned.apk
"$APKSIGNER" verify karas-tv-debug.apk && ls -la karas-tv-debug.apk
