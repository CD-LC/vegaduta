#!/usr/bin/env bash
# Write a throwaway env file for booting a compose stack in CI.
#
#   scripts/ci/compose-ci-env.sh <out-env-file> <compose-file>...
#
# Starts from the .env.example next to the first compose file (if any), then
# gives every variable the compose files declare as required (`${VAR:?...}` /
# `${VAR?...}`) and that is still empty a random value. APP_ENCRYPTION_KEY gets
# a valid key (base64 of 32 random bytes), which core refuses to start without.
# The values never leave the runner; never use this for a real deployment.
set -euo pipefail

out="${1:?usage: compose-ci-env.sh <out-env-file> <compose-file>...}"
shift
[[ $# -gt 0 ]] || { echo "compose-ci-env: no compose files given" >&2; exit 2; }

example="$(dirname "$1")/.env.example"
: > "$out"
if [[ -f "$example" ]]; then
  grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$example" >> "$out" || true
fi

has_value() {
  grep -Eq "^$1=.+" "$out"
}
set_value() {
  local key="$1" value="$2"
  if grep -Eq "^$key=" "$out"; then
    # Replace an empty KEY= line.
    local tmp; tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" 'BEGIN{FS=OFS="="} $1==k && $2=="" {print k "=" v; next} {print}' "$out" > "$tmp"
    mv "$tmp" "$out"
  else
    echo "$key=$value" >> "$out"
  fi
}

has_value APP_ENCRYPTION_KEY || set_value APP_ENCRYPTION_KEY "$(openssl rand -base64 32)"

while IFS= read -r var; do
  [[ -z "$var" ]] && continue
  has_value "$var" || set_value "$var" "ci$(openssl rand -hex 16)"
done < <(grep -hoE '\$\{[A-Za-z_][A-Za-z0-9_]*:?\?' "$@" | sed -E 's/^\$\{([A-Za-z0-9_]+).*/\1/' | sort -u)

echo "compose-ci-env: wrote $(grep -c '=' "$out") variables to $out"
