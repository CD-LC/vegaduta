#!/usr/bin/env bash
# Every ChannelType value must have a row in docs/channels/README.md (the channels overview), so a
# new channel cannot ship without being listed there with a link to its page.
# Usage: bash scripts/ci/channel-docs-check.sh
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
enum="$root/core/omnichannel/src/main/java/com/agenticai/core/omnichannel/session/ChannelType.java"
page="$root/docs/channels/README.md"
[ -f "$enum" ] || { echo "channel-docs-check: $enum not found" >&2; exit 1; }
[ -f "$page" ] || { echo "channel-docs-check: $page is missing (the channels overview)" >&2; exit 1; }
python3 -I - "$enum" "$page" <<'PY'
import re, sys
enum_src = open(sys.argv[1], encoding="utf-8").read()
body = enum_src[enum_src.index("public enum ChannelType"):]
body = re.sub(r"//[^\n]*", "", body)            # line comments between values (they may contain braces)
body = re.sub(r"(?s)/\*.*?\*/", "", body)
body = body[body.index("{") + 1 : body.index("}")]
values = re.findall(r"\b([A-Z][A-Z0-9_]*)\b", body)
page = open(sys.argv[2], encoding="utf-8").read()
rows = [l for l in page.splitlines() if l.startswith("|")]
missing = [v for v in values if not any(re.search(r"`%s`" % re.escape(v), r) for r in rows)]
if not values:
    sys.exit("channel-docs-check: found no ChannelType values (parser out of date)")
if missing:
    sys.exit("channel-docs-check: ChannelType value(s) with no row in docs/channels/README.md: " + ", ".join(missing))
print("channel-docs-check: OK (%d channel types, all in the overview)" % len(values))
PY
