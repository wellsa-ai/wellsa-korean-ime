#!/bin/bash
# Device-free JVM unit test of the pure decision logic (Gate). Needs only a JDK.
set -euo pipefail
cd "$(dirname "$0")"; rm -rf out-test && mkdir out-test
javac -d out-test src/ai/wellsa/koreanime/Gate.java test/GateTest.java
java -cp out-test GateTest
