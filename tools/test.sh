#!/bin/sh
# Runs the logic tests that need neither Android nor a Plex server.
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
pkg=src/uk/dollow/kiosk/plexphotos
javac --release 8 -Xlint:-options -d "$out" \
  "$pkg/Config.java" "$pkg/Dates.java" "$pkg/Html.java" "$pkg/Photo.java" "$pkg/PhotoCollector.java" "$pkg/Slide.java" \
  tests/uk/dollow/kiosk/plexphotos/LogicTest.java
java -ea -cp "$out" uk.dollow.kiosk.plexphotos.LogicTest
