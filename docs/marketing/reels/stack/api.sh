#!/bin/sh
# Usage: api.sh METHOD PATH [JSON-BODY]   -- calls core as the personal owner (local auth).
set -eu
STACK="$(cd "$(dirname "$0")" && pwd)"
CORE="${VD_CORE_URL:-http://127.0.0.1:8080}"
PW="$(cat "$STACK/owner-password.txt")"
TOK_FILE="$STACK/.token"
if [ ! -s "$TOK_FILE" ] || [ "$(find "$TOK_FILE" -mmin +4 | wc -l)" -gt 0 ]; then
  curl -sS -X POST $CORE/api/auth/local/login -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"owner@vegaduta.local\",\"password\":\"$PW\",\"type\":\"password\"}" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('access_token') or d)" > "$TOK_FILE"
fi
TOK="$(cat "$TOK_FILE")"
if [ $# -ge 3 ]; then
  curl -sS -X "$1" "$CORE$2" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "$3"
else
  curl -sS -X "$1" "$CORE$2" -H "Authorization: Bearer $TOK"
fi
