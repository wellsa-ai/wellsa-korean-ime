#!/bin/bash
# Android App Bundle (.aab) for Google Play, no Gradle: javac -> d8 -> aapt2 (proto) -> bundletool -> jarsigner.
# Requires: JDK 17+ (java, javac, jarsigner on PATH), Android SDK (platforms/android-36, build-tools 36.0.0), bundletool jar.
#   ANDROID_HOME   SDK root (default: $HOME/Android/Sdk)
#   BUILD_TOOLS    build-tools version (default: 36.0.0)
#   PLATFORM       platform (default: android-36)
#   BUNDLETOOL     path to bundletool-all-<ver>.jar (required)
# Signing (optional): KEYSTORE, KS_ALIAS and KS_PASS_FILE (file holding the password). Without them the .aab stays unsigned.
# Output: out/wellsa-korean-ime-0.3.3-vc6.aab. Never commit a keystore or password file.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/${BUILD_TOOLS:-36.0.0}; PLAT=$SDK/platforms/${PLATFORM:-android-36}/android.jar
: "${BUNDLETOOL:?BUNDLETOOL (path to bundletool jar) required}"
VER=0.3.3; VC=6
rm -rf out && mkdir -p out/classes out/base
javac -source 8 -target 8 -bootclasspath "$PLAT" -classpath "$PLAT" -Xlint:-options -nowarn -d out/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 34 --lib "$PLAT" --output out $(find out/classes -name '*.class')
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link --proto-format -o out/proto.apk --manifest AndroidManifest.xml -I "$PLAT" --min-sdk-version 34 --target-sdk-version 36 \
  --version-code "$VC" --version-name "$VER" out/res.zip
# Base module layout: manifest/AndroidManifest.xml, dex/, res/, resources.pb
(cd out/base && unzip -q ../proto.apk && mkdir manifest dex && mv AndroidManifest.xml manifest/ && cp ../classes.dex dex/classes.dex \
  && zip -q -r ../base.zip manifest dex res resources.pb)
java -jar "$BUNDLETOOL" build-bundle --modules=out/base.zip --output=out/unsigned.aab
AAB=out/wellsa-korean-ime-$VER-vc$VC.aab
cp out/unsigned.aab "$AAB"
if [ -z "${KEYSTORE:-}" ]; then echo "KEYSTORE not set: unsigned bundle at $AAB (sign it before uploading)"; exit 0; fi
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 -keystore "$KEYSTORE" -storepass:file "${KS_PASS_FILE:?KS_PASS_FILE required}" \
  -keypass:file "$KS_PASS_FILE" "$AAB" "${KS_ALIAS:?KS_ALIAS required}"
jarsigner -verify "$AAB" | head -2
shasum -a 256 "$AAB"
