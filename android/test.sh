#!/bin/bash
# Device-free JVM unit test of the pure decision logic (Gate). Needs only a JDK.
set -euo pipefail
cd "$(dirname "$0")"; rm -rf out-test && mkdir out-test
javac -d out-test src/ai/wellsa/koreanime/Gate.java test/GateTest.java
java -cp out-test GateTest
# Version consistency: manifest versionName must match MainActivity.VERSION.
MV=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
grep -q "VERSION = \"$MV\"" src/ai/wellsa/koreanime/MainActivity.java && echo "ok   version $MV matches MainActivity" || { echo "FAIL version mismatch"; exit 1; }
! grep -q 'permission.INTERNET' AndroidManifest.xml && echo "ok   no INTERNET permission" || { echo "FAIL INTERNET"; exit 1; }
