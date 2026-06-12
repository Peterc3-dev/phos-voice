#!/usr/bin/env bash
# Manual APK build (no gradle): javac -> d8 -> aapt2 link -> zipalign -> apksigner.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK=/home/raz/Android/Sdk
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-36/android.jar
cd "$HERE"
rm -rf build && mkdir -p build/classes

echo "[1/8] javac"
javac -source 17 -target 17 -classpath "$AJAR" -d build/classes \
    src/io/cin/phosrec/RecActivity.java

echo "[2/8] d8 -> classes.dex"
"$BT/d8" --min-api 31 --release --lib "$AJAR" --output build \
    $(find build/classes -name '*.class')

echo "[3/8] aapt2 link -> base.apk"
"$BT/aapt2" link -I "$AJAR" --manifest AndroidManifest.xml \
    --min-sdk-version 31 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 -o build/base.apk

echo "[4/8] add classes.dex into apk"
(cd build && zip -qj base.apk classes.dex)

echo "[5/8] zipalign"
"$BT/zipalign" -p -f 4 build/base.apk build/aligned.apk

echo "[6/8] keystore (PERSISTENT — outside build/ so updates keep the same signature)"
KS="$HERE/phos.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" \
    -storepass android -keypass android -alias dbg -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Phos Debug" >/dev/null 2>&1

echo "[7/8] sign"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias dbg --out build/phos-btmic.apk build/aligned.apk

echo "[8/8] verify"
"$BT/apksigner" verify build/phos-btmic.apk && echo "SIGNATURE OK"
ls -lh build/phos-btmic.apk
