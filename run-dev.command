#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

"$ROOT/run-dev.sh" &
APP_PID=$!

sleep 2
open "http://localhost:5173" || true

wait "$APP_PID"
