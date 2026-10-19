#!/usr/bin/env bash
# Devcontainer post-create: secrets, infra up, dependencies warm.
# Tolerant on purpose (|| echo on the heavy steps): a slow first build must not
# leave the container in a failed-create state; re-run any piece by hand.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

echo "==> Git hooks"
git config core.hooksPath .githooks || true

echo "==> Generating deploy/compose/.env and starting the infra subset (postgres, valkey, keycloak)"
bash scripts/dev-up.sh || echo "!! dev-up.sh failed - re-run it (docker-in-docker may still be starting)"

echo "==> Warming JS dependencies (web + web-admin)"
(cd web && npm ci --prefer-offline --no-audit --no-fund) || echo "!! web npm ci failed - re-run in web/"
(cd web-admin && npm ci --prefer-offline --no-audit --no-fund) || echo "!! web-admin npm ci failed - re-run in web-admin/"

echo "==> Priming the Maven repository (core compile, no tests)"
(cd core && mvn -q -B -ntp -DskipTests compile) || echo "!! mvn compile failed - re-run in core/"

cat <<'MSG'

Devcontainer ready. Infra runs via docker-in-docker; secrets are in deploy/compose/.env.
Run the apps with hot reload (see scripts/dev-up.sh for the env vars core needs):
  core:  cd core && mvn -pl app -am spring-boot:run    -> :8080
  web:   cd web && npm run dev                         -> :3000
  admin: cd web-admin && npm run dev                   -> :3001
Or run everything in containers: ./scripts/vegaduta up --lite --build
MSG
