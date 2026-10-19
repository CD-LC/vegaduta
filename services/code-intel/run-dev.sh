#!/usr/bin/env bash
# Local dev runner for the code-intel microservice.
#   cp .env.example .env   # first time, then edit
#   ./run-dev.sh
# Loads .env (if present) into the environment and starts uvicorn on :8090.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a            # export everything sourced below
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

PORT="${CODE_INTEL_PORT:-8090}"
echo "code-intel starting on :$PORT (auth=${CODE_INTEL_AUTH_MODE:-internal}, exec=${CODE_INTEL_ALLOW_EXEC:-false})"
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --reload
