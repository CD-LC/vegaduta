#!/usr/bin/env bash
# startup-profile.sh - turn what a core boot left behind into a phase table.
#
# Inputs (any combination; each missing one just skips its section):
#   --log FILE    the application log of a boot. Read for: the Spring "Started ... in N seconds" line, the
#                 active profiles, Flyway execution times, the "startup-profile:" lines that
#                 StartupProfilingReporter writes at readiness (app.startup.profiling.enabled=true), and,
#                 when the boot ran with DEBUG=true / --debug, the CONDITIONS EVALUATION REPORT.
#   --dump FILE   the JSON timeline (app.startup.profiling.dump-file), same shape as /actuator/startup.
#                 Gives bean-instantiate counts and times, and the time spent in a few named beans
#                 (entityManagerFactory = Hibernate bootstrap, flywayInitializer = Flyway, dataSource).
#   --jar FILE    a Spring Boot app jar. A STATIC scan of its classes (no JVM): how many @Entity classes and
#                 Spring Data repository interfaces are on the classpath, and the biggest libraries. This is
#                 what the context CAN manage, not what it did manage; it is a heuristic over class files.
#   --top N       how many rows in the "slowest" tables (default 10)
#   -h | --help
#
# Used by scripts/ci/boot-check.sh (--profile-startup / --conditions) and usable alone on a saved log.
# Read-only: it never starts anything and never writes outside stdout. Needs python3 for --dump and --jar.
#
# Exit: 0 = a table was printed, 2 = bad usage or nothing to read.
set -uo pipefail

LOG=""
DUMP=""
JAR=""
TOP=10

while [ $# -gt 0 ]; do
  case "$1" in
    --log) shift; LOG="${1:-}" ;;
    --dump) shift; DUMP="${1:-}" ;;
    --jar) shift; JAR="${1:-}" ;;
    --top) shift; TOP="${1:-10}" ;;
    -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "startup-profile: unknown option '$1' (see --help)" >&2; exit 2 ;;
  esac
  shift
done

case "$TOP" in ''|*[!0-9]*) echo "startup-profile: --top expects a number" >&2; exit 2 ;; esac
if [ -z "$LOG" ] && [ -z "$DUMP" ] && [ -z "$JAR" ]; then
  echo "startup-profile: give at least one of --log, --dump, --jar (see --help)" >&2
  exit 2
fi
for f in "$LOG" "$DUMP" "$JAR"; do
  if [ -n "$f" ] && [ ! -r "$f" ]; then echo "startup-profile: cannot read '$f'" >&2; exit 2; fi
done
if { [ -n "$DUMP" ] || [ -n "$JAR" ]; } && ! command -v python3 >/dev/null 2>&1; then
  echo "startup-profile: python3 not found (needed for --dump and --jar)" >&2
  exit 2
fi

hr() { echo "---- $* ----"; }

# ------------------------------------------------------------------ log

if [ -n "$LOG" ]; then
  hr "log: $(basename "$LOG")"
  grep -E "The following [0-9]+ profiles? (is|are) active|No active profile" "$LOG" | tail -1 | sed -E 's/^.*(The following|No active)/\1/'
  grep -E "Started [A-Za-z]+ in [0-9.]+ seconds" "$LOG" | tail -1 | sed 's/^.*\(Started\)/\1/'
  grep -E "startup-profile: ready in" "$LOG" | tail -1 | sed 's/^.*startup-profile:/ready (all runners done):/'
  echo "Flyway:"
  if grep -qE "Successfully applied [0-9]+ migrations?|Migrating schema" "$LOG"; then
    grep -E "Successfully applied [0-9]+ migrations?|now at version|Successfully validated" "$LOG" \
      | sed -E 's/^.*(Successfully|Schema)/  \1/' | sort | uniq -c | sort -rn | head -6
  else
    echo "  (no Flyway migration lines in this log)"
  fi
  grep -E "Hikari.*Start completed|HHH000204|Initialized JPA EntityManagerFactory" "$LOG" | head -3 | sed 's/^/  /'
  if grep -q "CONDITIONS EVALUATION REPORT" "$LOG"; then
    hr "conditions report"
    python3 -I - "$LOG" <<'PY'
import re, sys
text = open(sys.argv[1], errors="replace").read().splitlines()
section, pos, neg, exc, unc = None, [], [], [], []
for line in text:
    s = line.strip()
    if s == "Positive matches:": section = pos; continue
    if s == "Negative matches:": section = neg; continue
    if s == "Exclusions:": section = exc; continue
    if s == "Unconditional classes:": section = unc; continue
    if section is None:
        continue
    # an auto-configuration / condition entry is a 3-space-indented line ending with ":" or "matched:"
    m = re.match(r"^(?:.*? )?\s{3}([A-Za-z0-9_.$]+)(?: matched)?:\s*$", line)
    if m and not line.startswith("      "):
        section.append(m.group(1))
print("  positive matches: %d   negative matches: %d   exclusions: %d   unconditional: %d"
      % (len(pos), len(neg), len(exc), len(unc)))
mcp = [n for n in pos if "McpServer" in n or "mcp.server" in n]
mcp_neg = [n for n in neg if "McpServer" in n or "mcp.server" in n]
print("  Spring AI MCP-server auto-configurations that MATCHED: %s" % (", ".join(mcp) or "none"))
print("  Spring AI MCP-server auto-configurations that did NOT match: %s" % (", ".join(mcp_neg) or "none listed"))
PY
  fi
fi

# ----------------------------------------------------------------- dump

if [ -n "$DUMP" ]; then
  hr "timeline: $(basename "$DUMP")"
  python3 -I - "$DUMP" "$TOP" <<'PY'
import json, sys
from collections import defaultdict
from datetime import datetime

def parse_ts(s):
    s = s.replace("Z", "+00:00")
    if "." in s:  # python < 3.11 wants at most 6 fraction digits
        head, rest = s.split(".", 1)
        frac = ""
        i = 0
        while i < len(rest) and rest[i].isdigit():
            frac += rest[i]; i += 1
        s = head + "." + frac[:6].ljust(6, "0") + rest[i:]
    return datetime.fromisoformat(s)

def secs(d):  # ISO-8601 duration like PT1.234S / PT1M2.5S
    d = d[2:] if d.startswith("PT") else d
    total, num = 0.0, ""
    for ch in d:
        if ch.isdigit() or ch == ".": num += ch
        else:
            total += float(num or 0) * {"H": 3600, "M": 60, "S": 1}.get(ch, 0); num = ""
    return total

top = int(sys.argv[2])
data = json.load(open(sys.argv[1]))
events = data["timeline"]["events"]
steps = {}
for e in events:
    st = e["startupStep"]
    tags = {t["key"]: t["value"] for t in st.get("tags", [])}
    steps[st["id"]] = dict(name=st["name"], parent=st.get("parentId"), total=secs(e["duration"]), tags=tags,
                           start=parse_ts(e["startTime"]), end=parse_ts(e["endTime"]))
child = defaultdict(float)
for s in steps.values():
    if s["parent"] is not None: child[s["parent"]] += s["total"]
for i, s in steps.items(): s["self"] = max(0.0, s["total"] - child.get(i, 0.0))

wall = (max(s["end"] for s in steps.values()) - min(s["start"] for s in steps.values())).total_seconds() if steps else 0
print("  steps recorded: %d   recorded wall span: %.1f s" % (len(steps), wall))

by_name = defaultdict(lambda: [0.0, 0])
for s in steps.values():
    by_name[s["name"]][0] += s["self"]; by_name[s["name"]][1] += 1
print("  self time by step name (the recorder nests steps, so self time does not double count):")
for n, (t, c) in sorted(by_name.items(), key=lambda kv: -kv[1][0])[:top]:
    print("    %8.1f ms  x%-6d %s" % (t * 1000, c, n))

inst = [s for s in steps.values() if s["name"] == "spring.beans.instantiate"]
print("  beans instantiated: %d" % len(inst))
def suffix_count(suf): return sum(1 for s in inst if s["tags"].get("beanName", "").endswith(suf))
print("    by name suffix: Repository=%d  Controller=%d  Service=%d  Config/Configuration=%d"
      % (suffix_count("Repository"), suffix_count("Controller"), suffix_count("Service"),
         suffix_count("Config") + suffix_count("Configuration")))
named = {"entityManagerFactory": "Hibernate bootstrap (JPA EntityManagerFactory)",
         "flywayInitializer": "Flyway (migrate at bean init)", "flyway": "Flyway bean",
         "dataSource": "DataSource / Hikari", "liquibase": "Liquibase (should be absent)"}
print("  named beans (TOTAL time includes everything nested inside, e.g. Flyway runs inside its initializer):")
for bean, label in named.items():
    rows = [s for s in inst if s["tags"].get("beanName") == bean]
    if rows:
        print("    %8.1f ms total  %8.1f ms self  %s [%s]" % (
            sum(r["total"] for r in rows) * 1000, sum(r["self"] for r in rows) * 1000, label, bean))
repos = [s for s in inst if s["tags"].get("beanName", "").endswith("Repository")]
print("  repository beans: %d instantiated, %.1f ms total" % (len(repos), sum(r["total"] for r in repos) * 1000))
print("  slowest bean instantiations by SELF time:")
for s in sorted(inst, key=lambda x: -x["self"])[:top]:
    print("    %8.1f ms self  %8.1f ms total  %s" % (s["self"] * 1000, s["total"] * 1000, s["tags"].get("beanName", "?")))
PY
fi

# ------------------------------------------------------------------ jar

if [ -n "$JAR" ]; then
  hr "jar (static scan, heuristic): $(basename "$JAR")"
  python3 -I - "$JAR" "$TOP" <<'PY'
import io, os, sys, zipfile
top = int(sys.argv[2])
ENTITY = b"Ljakarta/persistence/Entity;"
REPO_MARKERS = (b"org/springframework/data/repository/Repository",
                b"org/springframework/data/jpa/repository/JpaRepository",
                b"org/springframework/data/repository/CrudRepository",
                b"org/springframework/data/repository/PagingAndSortingRepository",
                b"org/springframework/data/repository/ListCrudRepository")
count = {"classes": 0, "entities": 0, "repos": 0}

def scan_class(name, data):
    count["classes"] += 1
    if ENTITY in data:
        count["entities"] += 1
    if name.endswith("Repository.class") and any(m in data for m in REPO_MARKERS):
        count["repos"] += 1

outer = zipfile.ZipFile(sys.argv[1])
libs, first_party = [], 0
for info in outer.infolist():
    n = info.filename
    if n.startswith("BOOT-INF/classes/") and n.endswith(".class"):
        scan_class(n, outer.read(n))
    elif n.endswith(".jar"):
        libs.append((info.file_size, n.rsplit("/", 1)[-1]))
        if "SNAPSHOT" in n:  # this reactor's modules; third-party libraries carry release versions
            first_party += 1
            inner = zipfile.ZipFile(io.BytesIO(outer.read(n)))
            for member in inner.infolist():
                if member.filename.endswith(".class"):
                    scan_class(member.filename, inner.read(member.filename))
print("  jar: %.1f MB on disk, %.1f MB uncompressed; %d nested libraries, %d of them first-party (SNAPSHOT)"
      % (os.path.getsize(sys.argv[1]) / 1e6, sum(i.file_size for i in outer.infolist()) / 1e6, len(libs), first_party))
print("  first-party classes scanned: %d" % count["classes"])
print("  @Entity classes on the classpath: %d   Spring Data repository interfaces (name ends Repository): %d"
      % (count["entities"], count["repos"]))
aws = [(s, n) for s, n in libs if n.startswith(("kms-", "rds-"))]
print("  AWS KMS/RDS SDK jars: %s (%.1f MB uncompressed)"
      % (", ".join(n for _, n in aws) or "none", sum(s for s, _ in aws) / 1e6))
print("  largest libraries (uncompressed):")
for size, n in sorted(libs, reverse=True)[:top]:
    print("    %7.1f MB  %s" % (size / 1e6, n))
PY
fi
exit 0
