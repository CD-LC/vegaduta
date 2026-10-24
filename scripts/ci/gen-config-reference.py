#!/usr/bin/env python3
"""Generate docs/engineering/CONFIG-REFERENCE.generated.md and ratchet undocumented properties.

Scans, under core/:
  * application*.yml            every leaf key, flattened (source: yml)
  * @Value("${key:default}")    (source: @Value)
  * @ConfigurationProperties    the prefix plus its simple private fields (source: props)

A property is documented when any docs/**/*.md (except this generated file and archive/) or
deploy/compose/.env.example mentions it (dotted, kebab, relaxed or
ENV_VAR form) or mentions a dotted ancestor of it. Undocumented names that exist today live in
scripts/ci/config-reference-allowlist.txt. The allowlist can only shrink.

Usage:
  gen-config-reference.py            regenerate the markdown reference
  gen-config-reference.py --check    fail on a NEW undocumented property or a stale allowlist line
  gen-config-reference.py --update-allowlist   rewrite the allowlist (only to REMOVE lines;
                                               refuses to add)
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core"
DOC = ROOT / "docs" / "configuration.md"
OUT = ROOT / "docs" / "engineering" / "CONFIG-REFERENCE.generated.md"
ALLOW = ROOT / "scripts" / "ci" / "config-reference-allowlist.txt"

SKIP_DIRS = {"target", "node_modules", ".git"}


def files(pattern):
    for p in sorted(CORE.rglob(pattern)):
        if not SKIP_DIRS.intersection(p.parts):
            yield p


def rel(p):
    return str(p.relative_to(ROOT))


def scan_yml():
    found = {}
    for p in files("application*.yml"):
        if "/src/main/" not in str(p) and "/src/test/" in str(p):
            continue
        stack = []  # (indent, key)
        for raw in p.read_text(encoding="utf-8").splitlines():
            if raw.strip().startswith("#") or not raw.strip() or raw.strip() == "---":
                continue
            m = re.match(r"^(\s*)([A-Za-z0-9_.\-\[\]\"']+)\s*:(.*)$", raw)
            if not m:
                continue
            indent, key, rest = len(m.group(1)), m.group(2).strip("\"'"), m.group(3).strip()
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, key))
            if rest and not rest.startswith("#") and not rest.startswith("|") and rest != ">":
                name = ".".join(k for _, k in stack)
                if re.match(r"^[A-Za-z]", name):
                    found.setdefault(name, (set(), ""))[0].add(rel(p))
    return found


VALUE_RE = re.compile(r'@Value\(\s*"\$\{([A-Za-z][A-Za-z0-9_.\-]*)(?::([^}]*))?\}"\s*\)')
CP_RE = re.compile(r'@ConfigurationProperties\(\s*(?:prefix\s*=\s*|value\s*=\s*)?"([A-Za-z0-9_.\-]+)"')
FIELD_RE = re.compile(r"^\s*private\s+(?!static)(?!final\s+static)[\w<>\[\],.? ]+\s+(\w+)\s*(?:=[^;]*)?;", re.M)


def kebab(s):
    return re.sub(r"(?<!^)(?=[A-Z])", "-", s).lower()


def scan_java():
    found = {}
    for p in files("*.java"):
        if "/src/test/" in str(p):
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in VALUE_RE.finditer(text):
            found.setdefault(m.group(1), (set(), m.group(2) or ""))[0].add(rel(p))
        for m in CP_RE.finditer(text):
            prefix = m.group(1)
            body = text[m.end():]
            # fields of the annotated class only: a nested helper class (`Recall`, `Forget`) is bound under a
            # key of its own, so its field names are not keys of this prefix
            decl = re.search(r"\bclass\s+\w+", body)
            nested = re.search(r"\bclass\s+\w+", body[decl.end():]) if decl else None
            if decl and nested:
                body = body[:decl.end() + nested.start()]
            fields = list(FIELD_RE.finditer(body))
            if not fields:
                found.setdefault(prefix, (set(), ""))[0].add(rel(p))  # bound only through nested types or a map
            for f in fields:
                found.setdefault(prefix + "." + kebab(f.group(1)), (set(), ""))[0].add(rel(p))
    return found


def collect():
    merged = {}
    for src in (scan_yml(), scan_java()):
        for k, (paths, default) in src.items():
            e = merged.setdefault(k, [set(), ""])
            e[0] |= paths
            e[1] = e[1] or default
    return merged


def norm(s):
    return re.sub(r"[^a-z0-9.]", "", s.lower().replace("_", ".").replace("-", ""))


def doc_tokens():
    text = ""
    for f in sorted((ROOT / "docs").rglob("*.md")):
        if f == OUT or "archive" in f.parts:
            continue
        text += f.read_text(encoding="utf-8", errors="replace") + "\n"
    for f in (ROOT / "deploy" / "compose" / ".env.example",):
        if f.exists():
            text += f.read_text(encoding="utf-8", errors="replace")
    toks = set(re.findall(r"[A-Za-z0-9_.\-]+", text))
    # a trailing dot ends a sentence or a family row (`app.models.defaults.<provider>`): the ancestor is named
    return {norm(t.strip(".")) for t in toks if len(t.strip(".")) > 2}


def documented(name, toks):
    n = norm(name)
    if n in toks:
        return True
    parts = n.split(".")
    if any(".".join(parts[:i]) in toks for i in range(2, len(parts))):
        return True
    # a nested group (`app.graph-memory.recall`) is documented through the keys beneath it
    return any(t.startswith(n + ".") for t in toks)


def render(props, toks):
    lines = [
        "# Configuration reference (generated)",
        "",
        "Generated by `scripts/ci/gen-config-reference.py`. Do not edit by hand. Properties found in",
        "`core/**/application*.yml`, `@Value` and `@ConfigurationProperties`. The hand-written guide is",
        "[configuration.md](../configuration.md); the Documented column says whether the docs or .env.example mention it.",
        "",
        f"{len(props)} properties.",
        "",
        "| Property | Default | Documented | Source |",
        "|---|---|---|---|",
    ]
    for name in sorted(props):
        paths, default = props[name]
        d = default.replace("|", "\\|") if default else ""
        src = ", ".join(sorted(paths)[:2]) + (" +%d" % (len(paths) - 2) if len(paths) > 2 else "")
        lines.append(f"| `{name}` | {('`' + d + '`') if d else ''} | {'yes' if documented(name, toks) else 'no'} | {src} |")
    return "\n".join(lines) + "\n"


def read_allow():
    if not ALLOW.exists():
        return set()
    return {l.strip() for l in ALLOW.read_text().splitlines() if l.strip() and not l.startswith("#")}


HEADER = """# Undocumented configuration properties (ADR-0019, architecture bet 13).
#
# Properties that exist in core/ but are not mentioned in docs/ or .env.example.
# THIS FILE CAN ONLY SHRINK. A new property must be documented in docs/configuration.md instead
# of being added here. Delete a line once its property is documented or removed.
"""


def main():
    props = collect()
    toks = doc_tokens()
    undocumented = {n for n in props if not documented(n, toks)}
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "--check":
        allow = read_allow()
        new = sorted(undocumented - allow)
        stale = sorted(allow - undocumented)
        if new:
            print("Undocumented NEW configuration properties (document them in docs/configuration.md or .env.example):")
            print("\n".join("  " + n for n in new))
        if stale:
            print("Allowlist lines that are now documented or gone (delete from config-reference-allowlist.txt):")
            print("\n".join("  " + n for n in stale))
        sys.exit(1 if new or stale else 0)
    if mode == "--init-allowlist":
        ALLOW.write_text(HEADER + "\n".join(sorted(undocumented)) + "\n")
        print(f"wrote {len(undocumented)} allowlist lines")
        return
    if mode == "--update-allowlist":
        allow = read_allow()
        ALLOW.write_text(HEADER + "\n".join(sorted(allow & undocumented)) + "\n")
        print(f"allowlist now {len(allow & undocumented)} lines (was {len(allow)})")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(render(props, toks), encoding="utf-8")
    print(f"wrote {rel(OUT)}: {len(props)} properties, {len(undocumented)} undocumented")


if __name__ == "__main__":
    main()
