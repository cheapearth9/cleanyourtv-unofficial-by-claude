#!/usr/bin/env bash
# Runs the JVM self-tests: the ADB client against a mock adbd, and the clean-up logic against a fake Android 7/14 TV.
# Needs only a JDK 17 and /bin/sh. No Android SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -Xlint:-options -d "$OUT" $(find phone/src/main/java/tv/clean/phone/adb phone/src/main/java/tv/clean/phone/core phone/src/test/java -name '*.java')
java -cp "$OUT" tv.clean.phone.adb.AdbSelfTest
java -cp "$OUT" tv.clean.phone.core.CoreSelfTest phone/src/main/assets/db/models.txt
