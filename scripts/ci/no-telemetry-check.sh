#!/usr/bin/env bash
# No-telemetry gate. VegaDuta sends no usage analytics, crash reports or tracking pixels
# to third parties: a self-hosted install talks only to the services its operator
# configured. This fails when web/, core/ or services/ reference a known analytics or
# tracking host or SDK. The allow-list is deliberately empty
# (scripts/ci/no-telemetry-allowlist.txt): if a feature truly needs one of these, it
# needs an explicit decision, an opt-in switch that defaults to off, and a docs entry,
# and then a "<path><TAB><reason>" line there.
#
# Out of scope: OpenTelemetry (operator-configured OTLP export to the operator's own
# collector), web/messages (translated copy that may name other products), lock files,
# node_modules, build output, db/migration (applied migrations are never edited).
#
# Usage: bash scripts/ci/no-telemetry-check.sh
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
ALLOWLIST=scripts/ci/no-telemetry-allowlist.txt

# Hosts and package/SDK identifiers of common analytics, session-replay, error-reporting
# and ad/tracking vendors, matched case-insensitively as fixed strings (a regex over this
# many alternatives is ~30x slower on a repository this size).
FIXED=(
  posthog api.segment.io api.segment.com cdn.segment.com @segment/ mixpanel amplitude.com @amplitude/
  google-analytics.com googletagmanager.com googleadservices doubleclick.net react-ga
  @vercel/analytics @vercel/speed-insights va.vercel-scripts.com plausible.io simpleanalytics usefathom
  cdn.matomo matomo.php sentry.io @sentry/ datadoghq.com datadoghq.eu @datadog/browser rum.browser-intake
  newrelic nr-data.net bugsnag logrocket hotjar fullstory heapanalytics clarity.ms smartlook mouseflow
  rudderstack statsig launchdarkly growthbook.io connect.facebook.net snap.licdn.com static.ads-twitter.com
  analytics.tiktok.com hs-scripts.com hs-analytics widget.intercom.io js.intercomcdn.com js.driftt.com
  crisp.chat app.pendo.io cdn.pendo.io umami.is
)
# Short words that also occur inside ordinary identifiers (scrollbar contains "rollbar"),
# so they are matched as whole words.
WORDS=(rollbar gtag fbq)

fixed_args=()
for t in "${FIXED[@]}"; do fixed_args+=(-e "$t"); done
word_args=()
for t in "${WORDS[@]}"; do word_args+=(-e "$t"); done

SCOPE=(
  web core services
  ':!**/node_modules/**' ':!**/target/**' ':!**/.next/**' ':!**/dist/**'
  ':!web/messages/**' ':!**/package-lock.json' ':!**/*.lock'
  ':!**/db/migration/**'
)

hits=$({ git grep -I -n -i -F --untracked --exclude-standard "${fixed_args[@]}" -- "${SCOPE[@]}" || true
         git grep -I -n -i -F -w --untracked --exclude-standard "${word_args[@]}" -- "${SCOPE[@]}" || true; } | sort -u)

if [ -f "$ALLOWLIST" ]; then
  while IFS=$'\t' read -r path _; do
    case "$path" in ''|'#'*) continue ;; esac
    hits=$(printf '%s\n' "$hits" | grep -v "^${path}:" || true)
  done < "$ALLOWLIST"
fi

if [ -n "$hits" ]; then
  echo "FAIL  analytics / tracking host or SDK referenced (VegaDuta ships no telemetry):" >&2
  printf '%s\n' "$hits" | cut -c1-200 | sed 's/^/        /' >&2
  echo "no-telemetry-check: remove it, or add a reasoned line to $ALLOWLIST after a maintainer decision." >&2
  exit 1
fi
echo "no-telemetry-check: ok"
