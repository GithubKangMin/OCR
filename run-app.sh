#!/usr/bin/env bash
set -euo pipefail

ROOT="/Users/kmg/Project/ocr"
JAR="$ROOT/backend/target/ocr-app.jar"

if [ ! -f "$JAR" ]; then
  echo "Jar not found. Build first: $ROOT/build-release.sh"
  exit 1
fi

cd "$ROOT/backend"
java -jar "$JAR" "$@"
