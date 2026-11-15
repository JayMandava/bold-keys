#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
JAR="${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}"
mkdir -p build/classes build/dex
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml
java com.sun.tools.javac.Main -source 8 -target 8 -classpath "$JAR" -d build/classes src/demo/draft/*.java
java sun.tools.jar.Main cf build/classes.jar -C build/classes .
"$BT/d8" --lib "$JAR" --min-api 33 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [[ ! -f build/ephemeral.jks ]]; then
  keytool -genkeypair -keystore build/ephemeral.jks -storepass android -keypass android -alias capture -dname 'CN=Disposable Capture' -keyalg RSA -keysize 2048 -validity 2 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks build/ephemeral.jks --ks-key-alias capture --ks-pass pass:android --out build/editor.apk build/aligned.apk
"$BT/apksigner" verify build/editor.apk
