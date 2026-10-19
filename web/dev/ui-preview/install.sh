#!/usr/bin/env bash
# Installs the LOCAL, DEV-ONLY UI preview into web/app/ui-kit/ (git-ignored) and regenerates the route loaders.
# Usage: cd web && bash dev/ui-preview/install.sh && npm run dev   ->  http://localhost:3000/ui-kit/pv
# Remove with: bash dev/ui-preview/install.sh --remove
set -euo pipefail
cd "$(dirname "$0")/../.."
APP=app/ui-kit
if [ "${1:-}" = "--remove" ]; then rm -rf "$APP/pv" "$APP/pv-lib"; echo removed; exit 0; fi
mkdir -p "$APP/pv/[[...slug]]" "$APP/pv-lib/fixtures"
cp dev/ui-preview/pv-page.tsx.tpl "$APP/pv/[[...slug]]/page.tsx"
cp dev/ui-preview/mock.ts.tpl "$APP/pv-lib/mock.ts"
cp dev/ui-preview/fixtures-index.ts.tpl "$APP/pv-lib/fixtures/index.ts"
# keep area fixture files already present; only seed core.ts if missing
[ -f "$APP/pv-lib/fixtures/core.ts" ] || cp dev/ui-preview/fixtures-core.ts.tpl "$APP/pv-lib/fixtures/core.ts"
python3 - <<'PY'
import os, re
routes = []
for root, _, files in os.walk("app"):
    if "page.tsx" not in files: continue
    r = root[3:] or "/"
    if "[" in r or r.startswith("/ui-kit") or r.startswith("/api") or r == "/": continue
    src = open(os.path.join(root, "page.tsx")).read()
    if re.search(r'^\s*["\']use client["\']', src[:600], re.M) and not any(x in src for x in ("next/headers", "seo-i18n", "generateMetadata")):
        routes.append(r)
lines = ["// GENERATED (dev preview only)", "/* eslint-disable */", "export const LOADERS: Record<string, () => Promise<{ default: any }>> = {"]
lines += [f'  "{r}": () => import("../..{r}/page"),' for r in sorted(set(routes))]
lines += ["};", "export const ROUTES = Object.keys(LOADERS);"]
open("app/ui-kit/pv-lib/loaders.ts", "w").write("\n".join(lines) + "\n")
print(len(routes), "client routes")
PY
echo "installed. Public route /ui-kit is already allowed by the auth gate, so no auth edit is needed."
