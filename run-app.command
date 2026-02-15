#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if [ ! -f "$ROOT/backend/target/ocr-app.jar" ]; then
  echo "[INFO] ocr-app.jar not found. Building release..."
  "$ROOT/build-release.sh"
fi

EXISTING_PID="$(lsof -tiTCP:8787 -sTCP:LISTEN 2>/dev/null || true)"
if [ -n "$EXISTING_PID" ]; then
  echo "[INFO] Stopping existing process on :8787 ($EXISTING_PID)"
  kill "$EXISTING_PID" 2>/dev/null || true
  sleep 1
fi

echo "[INFO] Starting OCR app..."
"$ROOT/run-app.sh" "$@" &
APP_PID=$!

sleep 2
open "http://localhost:8787" || true

wait "$APP_PID"
