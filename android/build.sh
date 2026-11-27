#!/bin/bash
# Manual pipeline, no Gradle: javac -> d8 -> aapt2 -> zipalign -> apksigner. No network needed.
# Requires: JDK 17+, Android SDK with platforms/android-35 and build-tools 35.0.0.
#   ANDROID_HOME   SDK root (default: $HOME/Android/Sdk)
#   BUILD_TOOLS    build-tools version (default: 35.0.0)
#   PLATFORM       platform (default: android-35)
# Signing (optional): set KEYSTORE, KS_ALIAS and KS_PASS_FILE (file holding the password).
#   Without them the script stops after producing an aligned, UNSIGNED apk (out/aligned.apk).
#   Create your own key once:  keytool -genkeypair -v -keystore my.jks -alias ime -keyalg RSA -keysize 4096 -validity 3650
# Never commit a keystore or password file.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/${BUILD_TOOLS:-35.0.0}; PLAT=$SDK/platforms/${PLATFORM:-android-35}/android.jar
VER=0.3.2
rm -rf out && mkdir -p out/classes out/res
javac -source 8 -target 8 -bootclasspath "$PLAT" -classpath "$PLAT" -Xlint:-options -nowarn -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 34 --lib "$PLAT" --output out $(find out/classes -name '*.class')
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/unsigned.apk --manifest AndroidManifest.xml -I "$PLAT" --min-sdk-version 34 --target-sdk-version 35 out/res.zip
(cd out && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 out/unsigned.apk out/aligned.apk
if [ -z "${KEYSTORE:-}" ]; then echo "KEYSTORE not set: unsigned apk at out/aligned.apk (sign it before installing)"; exit 0; fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KS_ALIAS:?KS_ALIAS required}" --ks-pass "file:${KS_PASS_FILE:?KS_PASS_FILE required}" --out "out/wellsa-korean-ime-$VER.apk" out/aligned.apk
"$BT/apksigner" verify "out/wellsa-korean-ime-$VER.apk"
sha256sum "out/wellsa-korean-ime-$VER.apk"
