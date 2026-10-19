#!/usr/bin/env bash
# Run the Java core locally (mvn spring-boot:run) with deploy/compose/.env loaded.
#
# Spring Boot does NOT read .env files (a Docker/Node convention) - a plain
# `mvn spring-boot:run` only sees the shell environment plus application.yml,
# so the secrets in deploy/compose/.env are invisible to it and the app dies on
# "APP_ENCRYPTION_KEY is not set". This sources deploy/compose/.env (the file
# `scripts/vegaduta secrets` generates) into the environment, then starts core
# against the compose stack's published ports. It stays the single
# (git-ignored) source of truth; host values come from application.yml's
# localhost defaults. Like the containers, core logs in as the
# least-privileged POSTGRES_APP_USER, never the superuser.
# Override the file with VEGADUTA_ENV_FILE.
#
# Usage:
#   ./scripts/run-core-local.sh
#   ./scripts/run-core-local.sh -Dspring-boot.run.jvmArguments="-Xmx2g"
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="${VEGADUTA_ENV_FILE:-$repo_root/deploy/compose/.env}"
core_dir="$repo_root/core"

if [[ ! -f "$env_file" ]]; then
  echo "$env_file not found - generate it with: scripts/vegaduta secrets" >&2
  exit 1
fi

# `set -a` marks every subsequently-assigned variable for export; sourcing the
# KEY=VALUE file then puts them all into the environment mvn inherits.
set -a
# shellcheck disable=SC1090
. "$env_file"
set +a

# Same database login as the containers (see deploy/postgres/ensure-app-role.sh).
if [[ -n "${POSTGRES_APP_USER:-}" && -n "${POSTGRES_APP_PASSWORD:-}" ]]; then
  export POSTGRES_USER="$POSTGRES_APP_USER" POSTGRES_PASSWORD="$POSTGRES_APP_PASSWORD"
fi

if [[ -z "${APP_ENCRYPTION_KEY:-}" ]]; then
  echo "WARNING: APP_ENCRYPTION_KEY is still empty after loading $env_file - core will refuse to start." >&2
fi

cd "$core_dir"
exec mvn -o -pl app spring-boot:run "$@"
