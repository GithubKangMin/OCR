#!/usr/bin/env bash
set -euo pipefail

ROOT="/Users/kmg/Project/ocr"

echo "[1/2] Starting backend on :8787"
(cd "$ROOT/backend" && mvn spring-boot:run) &
BACK_PID=$!

echo "[2/2] Starting frontend on :5173"
(cd "$ROOT/frontend" && npm run dev) &
FRONT_PID=$!

cleanup() {
  kill "$BACK_PID" "$FRONT_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait
