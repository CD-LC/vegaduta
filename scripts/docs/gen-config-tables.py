#!/usr/bin/env python3
"""Keep the key and default tables of docs/configuration.md equal to the code.

Source of truth (nothing is typed by hand):
  * core/*/src/main/resources/application*.yml   every leaf key with its raw value (`${ENV:default}`)
  * @Value("${key:default}") in core/*/src/main   the Java default; `" + CONST + "` pieces are resolved from
                                                  the `static final` constant they name
  * @ConfigurationProperties classes              the prefix and the literal initialiser of each field

Reads docs/configuration.md and finds every table row that starts with a backticked property key. In the
Appendix tables ("## Appendix: configuration property reference by module") the second cell is the Default.

Findings (a row is judged by its default cell only; the other cells are prose):
  broken   a default that renders as `"`, as `" + CONST + "` or as `(set in source)` for a key whose default the
           code knows (the generator that wrote the appendix printed the regular expression's capture, not the
           value)
  drift    an Appendix default that is none of the values the code gives the key
  empty    an Appendix row with no default although the code gives one (only with --fill-empty is it written)
  missing  a key the code reads that no docs/**/*.md or deploy/compose/.env.example names (a dotted ancestor
           counts, as in scripts/ci/gen-config-reference.py)

  python3 scripts/docs/gen-config-tables.py                dry run: print the findings and the cell edits
  python3 scripts/docs/gen-config-tables.py --check        exit 1 on a broken or drifted default or a missing key
  python3 scripts/docs/gen-config-tables.py --write        rewrite ONLY the default cell of broken and drifted
                                                           Appendix rows, in place; every other byte is kept
  python3 scripts/docs/gen-config-tables.py --write --fill-empty   also fill empty default cells
  python3 scripts/docs/gen-config-tables.py --missing-table        rows for the missing keys, ready to paste

The write is a single read-modify-write of the file in one process, touching only the cells it reports, so it
cannot clobber a concurrent edit of a different row more than a plain editor save could. Python 3.9, standard
library only. Unit tests: scripts/docs/test_gen_config_tables.py.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core"
DOC = ROOT / "docs" / "configuration.md"
APPENDIX = "## Appendix: configuration property reference by module"
SKIP_DIRS = {"target", "node_modules", ".git"}
# credential-like: the default is never reproduced (matches the rule the appendix states)
SECRET_TAIL = re.compile(r"(secret|password|passphrase|token|api-key|apikey|secret-key|access-key|private-key|signing-key|credentials?)$")
BROKEN_CELL = re.compile(r'^`?"`?$|"\s*\+|\+\s*"|^`?\(set in source\)`?$')
KEY_ROW = re.compile(r"^\|\s*`([A-Za-z][A-Za-z0-9_.\-]*)`\s*\|")


# ---------------------------------------------------------------------------------------------- code scanning
def java_files():
    for p in sorted(CORE.rglob("*.java")):
        if SKIP_DIRS.intersection(p.parts) or "/src/test/" in str(p) or "/src/main/" not in str(p):
            continue
        yield p


def yml_files():
    for p in sorted(CORE.rglob("application*.yml")):
        if SKIP_DIRS.intersection(p.parts) or "/src/main/" not in str(p):
            continue
        yield p


def split_placeholder(raw):
    """`${ENV:default}` (nested allowed) -> (env, default); a plain value -> (None, value)."""
    raw = raw.strip()
    if not (raw.startswith("${") and raw.endswith("}")):
        return None, raw
    inner, depth, cut = raw[2:-1], 0, -1
    for i, c in enumerate(inner):
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        elif c == ":" and depth == 0:
            cut = i
            break
    if cut < 0:
        return inner, ""
    env, default = inner[:cut], inner[cut + 1:]
    nested_env, nested_default = split_placeholder(default) if default.startswith("${") else (None, default)
    return env, nested_default if nested_env else default


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value


def scan_yml():
    """key -> list of raw values (unquoted)."""
    found = {}
    for p in yml_files():
        stack = []
        for raw in p.read_text(encoding="utf-8").splitlines():
            line = raw.rstrip()
            if not line.strip() or line.strip().startswith("#") or line.strip() == "---":
                continue
            m = re.match(r"^(\s*)([A-Za-z0-9_.\-\[\]\"']+)\s*:(.*)$", line)
            if not m:
                continue
            indent, key, rest = len(m.group(1)), m.group(2).strip("\"'"), m.group(3).strip()
            while stack and stack[-1][0] >= indent:
                stack.pop()
            stack.append((indent, key))
            if rest and not rest.startswith("#") and not rest.startswith("|") and rest != ">":
                value = re.sub(r"\s+#\s.*$", "", rest) if not rest.startswith(("'", '"')) else rest
                name = ".".join(k for _, k in stack)
                found.setdefault(name, []).append(unquote(value))
    return found


CONST_DEF = re.compile(r"static\s+final\s+[\w<>\[\]]+\s+(\w+)\s*=\s*([^;]+);")
PIECE = re.compile(r'"\s*\+\s*([\w.]+)\s*\+\s*"')
VALUE_RE = re.compile(r'@Value\(\s*"\$\{([A-Za-z][A-Za-z0-9_.\-]*)(?::((?:[^}"]|"\s*\+\s*[\w.]+\s*\+\s*")*))?\}"\s*\)')
CP_RE = re.compile(r'@ConfigurationProperties\(\s*(?:prefix\s*=\s*|value\s*=\s*)?"([A-Za-z0-9_.\-]+)"')
FIELD_RE = re.compile(r"^\s*private\s+(?!static)(?!final\s+static)[\w<>\[\],.? ]+\s+(\w+)\s*=\s*([^;]*);", re.M)


def literal(expr):
    """The value of a Java literal initialiser, or None when it is not a plain literal."""
    expr = expr.strip()
    if re.fullmatch(r'"(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*', expr):
        return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', expr))
    if re.fullmatch(r"-?[\d_]+(\.\d+)?[LlDdFf]?", expr):
        return re.sub(r"[_LlDdFf]$", "", expr.replace("_", ""))
    if expr in ("true", "false"):
        return expr
    return None


def kebab(s):
    return re.sub(r"(?<!^)(?=[A-Z])", "-", s).lower()


def scan_java():
    """(key -> list of defaults, key -> source path), constants resolved."""
    texts = {p: p.read_text(encoding="utf-8", errors="replace") for p in java_files()}
    constants = {}
    for p, text in texts.items():
        for m in CONST_DEF.finditer(text):
            value = literal(m.group(2))
            if value is not None:
                constants.setdefault(m.group(1), {})[p] = value
    defaults, sources, prefixes = {}, {}, set()
    for p, text in texts.items():
        def resolve(m):
            name = m.group(1).split(".")[-1]
            local = constants.get(name, {})
            hit = local.get(p) if p in local else (next(iter(local.values())) if len(local) == 1 else None)
            return hit if hit is not None else m.group(0)

        for m in VALUE_RE.finditer(text):
            default = PIECE.sub(resolve, m.group(2) or "")
            defaults.setdefault(m.group(1), []).append(default)
            sources.setdefault(m.group(1), str(p.relative_to(ROOT)))
        for m in CP_RE.finditer(text):
            prefix = m.group(1)
            prefixes.add(prefix)
            sources.setdefault(prefix, str(p.relative_to(ROOT)))
            body = text[m.end():]
            decl = re.search(r"\bclass\s+\w+", body)
            nested = re.search(r"\bclass\s+\w+", body[decl.end():]) if decl else None
            if decl and nested:
                body = body[:decl.end() + nested.start()]  # fields of the top-level class only
            for f in FIELD_RE.finditer(body):
                key = prefix + "." + kebab(f.group(1))
                sources.setdefault(key, str(p.relative_to(ROOT)))
                value = literal(f.group(2))
                if value is not None:
                    defaults.setdefault(key, []).append(value)
    return defaults, sources, prefixes


class Code:
    """What the code says about each key."""

    def __init__(self):
        self.yml = scan_yml()
        self.java, self.sources, self.prefixes = scan_java()

    def candidates(self, key):
        """Every default the code gives `key`, as a doc cell may show it (raw yml, resolved yml, @Value)."""
        out = []
        for raw in self.yml.get(key, []):
            out.append(raw)
            _, resolved = split_placeholder(raw)
            out.append(resolved)
        out.extend(self.java.get(key, []))
        return [c for c in dict.fromkeys(out)]

    def preferred(self, key):
        """The default to write for `key`: the yml expression as the appendix shows it, else the Java default."""
        for raw in self.yml.get(key, []):
            return raw
        for value in self.java.get(key, []):
            return value
        return None

    def keys(self):
        return set(self.yml) | set(self.java) | self.prefixes


# ----------------------------------------------------------------------------------------------- doc reading
def split_cells(line):
    """Cells of a markdown table row, keeping escaped pipes inside a cell and the exact text of each."""
    body = line.rstrip("\n")
    cells, buf, i, in_code = [], [], 0, False
    body = body[1:] if body.startswith("|") else body
    while i < len(body):
        c = body[i]
        if c == "`":
            in_code = not in_code
        if c == "\\" and i + 1 < len(body) and body[i + 1] == "|":
            buf.append("\\|")
            i += 2
            continue
        if c == "|" and not in_code:
            cells.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    if buf and "".join(buf).strip():
        cells.append("".join(buf))
    return cells


def cell_value(cell):
    v = cell.strip()
    if v.startswith("`") and v.endswith("`") and len(v) >= 2:
        v = v[1:-1]
    return v.replace("\\|", "|")


def render_cell(value):
    return " `" + value.replace("|", "\\|") + "` " if value != "" else "  "


def doc_rows(lines):
    """(line index, key, cells, in_appendix) for every key row."""
    in_appendix = False
    for i, line in enumerate(lines):
        if line.startswith("## "):
            in_appendix = line.startswith(APPENDIX)
        m = KEY_ROW.match(line)
        if m:
            yield i, m.group(1), split_cells(line), in_appendix


def analyse(lines, code):
    findings = {"broken": [], "drift": [], "empty": [], "edits": {}}
    for i, key, cells, in_appendix in doc_rows(lines):
        if len(cells) < 2:
            continue
        secret = bool(SECRET_TAIL.search(key))
        candidates = code.candidates(key)
        shown = cell_value(cells[1])
        broken = bool(BROKEN_CELL.search(shown))
        if broken and key not in code.yml and key not in code.java:
            continue
        if broken and not secret and code.preferred(key) is not None:
            findings["broken"].append((i + 1, key, shown, code.preferred(key)))
            if in_appendix:
                findings["edits"][i] = (key, code.preferred(key))
        elif (in_appendix and not secret and shown and candidates and shown not in candidates
              and re.fullmatch(r"\s*`[^`]*`\s*", cells[1]) and not shown.endswith("...")
              and not any(c.startswith(shown[:-3]) for c in candidates if shown.endswith("..."))):
            findings["drift"].append((i + 1, key, shown, code.preferred(key)))
            findings["edits"][i] = (key, code.preferred(key))
        elif in_appendix and not secret and not shown and candidates and any(c for c in candidates):
            findings["empty"].append((i + 1, key, "", code.preferred(key)))
    return findings


def norm(s):
    return re.sub(r"[^a-z0-9.]", "", s.lower().replace("_", ".").replace("-", ""))


def doc_tokens():
    text = ""
    for f in sorted((ROOT / "docs").rglob("*.md")):
        if "archive" in f.parts or f.name == "CONFIG-REFERENCE.generated.md":
            continue
        text += f.read_text(encoding="utf-8", errors="replace") + "\n"
    env = ROOT / "deploy" / "compose" / ".env.example"
    if env.exists():
        text += env.read_text(encoding="utf-8", errors="replace")
    return tokens_of(text)


def tokens_of(text):
    """Normalised dotted tokens of a text. A trailing dot is dropped, so the family row `app.models.defaults.<provider>`
    names the ancestor `app.models.defaults` (the token the regular expression sees is `app.models.defaults.`)."""
    return {norm(t.strip(".")) for t in set(re.findall(r"[A-Za-z0-9_.\-]+", text)) if len(t.strip(".")) > 2}


def missing_keys(code):
    toks = doc_tokens()
    out = []
    for key in sorted(code.keys()):
        if key in code.prefixes and key not in code.yml and key not in code.java:
            continue
        n = norm(key)
        parts = n.split(".")
        if n in toks or any(".".join(parts[:i]) in toks for i in range(2, len(parts))):
            continue
        out.append(key)
    return out


def apply_edits(lines, edits):
    out = list(lines)
    for i, (key, value) in edits.items():
        cells = split_cells(out[i])
        cells[1] = render_cell(value)
        out[i] = "|" + "|".join(cells) + "|\n"
    return out


# ---------------------------------------------------------------------------------------------------- main
def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="exit 1 on a broken or drifted default or a missing key")
    ap.add_argument("--write", action="store_true", help="rewrite the default cell of broken and drifted Appendix rows")
    ap.add_argument("--fill-empty", action="store_true", help="with --write: also fill empty Appendix default cells")
    ap.add_argument("--missing-table", action="store_true", help="print table rows for the keys no doc names")
    args = ap.parse_args(argv)

    code = Code()
    lines = DOC.read_text(encoding="utf-8").splitlines(keepends=True)
    found = analyse(lines, code)
    if args.fill_empty:
        for ln, key, _, value in found["empty"]:
            found["edits"][ln - 1] = (key, value)
    missing = missing_keys(code)

    if args.missing_table:
        print("| Property | Default | Applies to | Meaning |\n|---|---|---|---|")
        for key in missing:
            default = code.preferred(key)
            src = code.sources.get(key, "core/app-config/src/main/resources/application.yml")
            print("| `%s` | %s | shapes that load the module | see source: `%s` |" % (
                key, ("`" + default.replace("|", "\\|") + "`") if default else "", src))
        return 0

    for label in ("broken", "drift", "empty"):
        for ln, key, shown, value in found[label]:
            print("%-6s %s:%d  %s  shows %r, code says %r" % (label, DOC.name, ln, key, shown, value))
    for key in missing:
        print("missing %s  (no docs/**/*.md or .env.example names it or an ancestor)" % key)
    print("summary: %d broken, %d drifted, %d empty, %d missing; %d cell edit(s) %s" % (
        len(found["broken"]), len(found["drift"]), len(found["empty"]), len(missing), len(found["edits"]),
        "written" if args.write else "pending (dry run)"))

    if args.write and found["edits"]:
        DOC.write_text("".join(apply_edits(lines, found["edits"])), encoding="utf-8")
    if args.check and (found["broken"] or found["drift"] or missing):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
