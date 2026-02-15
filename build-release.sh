#!/usr/bin/env bash
set -euo pipefail

ROOT="/Users/kmg/Project/ocr"
cd "$ROOT/backend"

mvn -Pbundle-frontend -DskipTests package

echo "Built: $ROOT/backend/target/ocr-app.jar"
