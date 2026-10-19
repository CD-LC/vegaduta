#!/usr/bin/env python3
"""Regenerate the scheduled-bean ownership tables in docs/deploy/DEPLOYMENT-SHAPES.md.

Source of truth (nothing inside the generated regions is hand-edited):
  * core/*/src/main/java/**.java   every class with a line-anchored @Scheduled method, its module, and
                                   whether each @Scheduled body calls tryRunExclusive (Postgres advisory
                                   lock) or carries @AllowUnlockedSchedule(reason = ...)
  * core/app-config/src/main/resources/application-shape-*.yml
                                   app.scheduling.skip-bean-classes / keep-bean-classes per shape, and the
                                   named pools (app.scheduling.pools / pool-lifecycle) of shape-jobs

The scanning rules mirror ScheduledJobsAreLockedOrAnnotatedTest (line-anchored @Scheduled, brace
matching for the method body). Python 3.9, standard library only.

  python3 scripts/docs/gen-shapes.py           rewrite the generated regions in place
  python3 scripts/docs/gen-shapes.py --check   exit 1 when the doc is stale (run by `make gates`)
  python3 scripts/docs/gen-shapes.py --print   show the regions without touching the doc
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core"
DOC = ROOT / "docs" / "deploy" / "DEPLOYMENT-SHAPES.md"
CFG = CORE / "app-config" / "src" / "main" / "resources"
LOCK_CALL = "tryRunExclusive"
BEGIN = "<!-- gen-shapes:begin {} -->"
END = "<!-- gen-shapes:end {} -->"
SCHEDULED = re.compile(r"(?m)^[ \t]*@Scheduled\b")
# the first type declaration at the start of a line (optionally preceded by same-line annotations), so the word
# "class" inside a javadoc or comment never ends the class-level annotation block early
CLASS_DECL = re.compile(
    r"(?m)^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]+)*(?:(?:public|protected|private|abstract|final|static|sealed|strictfp)\s+)*"
    r"(?:class|interface|enum|record)\s+\w+")


def find_close(src, start, open_c, close_c):
    depth = 0
    for i in range(start, len(src)):
        if src[i] == open_c:
            depth += 1
        elif src[i] == close_c:
            depth -= 1
            if depth == 0:
                return i
    return len(src) - 1


def java_string(text):
    """Concatenation of the Java string literals in text."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', text)
    return "".join(p.replace('\\"', '"').replace("\\\\", "\\") for p in parts)


def allow_reason(text):
    a = re.search(r"@AllowUnlockedSchedule\s*\(", text)
    if not a:
        return None
    end = find_close(text, a.end() - 1, "(", ")")
    reason = java_string(text[a.end():end]).strip()
    # Only the two known cross-reference tails ("; see the javadoc above.", ". See docs/....md.") are for the
    # source reader, not the table, and only at the very end. Any other "see" is part of the reason.
    reason = re.sub(r"[;.,]?\s+[Ss]ee (?:the javadoc above|docs/\S*?)\.?$", "", reason)
    return reason if reason.endswith(".") else reason + "."


def scan_class(path):
    src = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    pkg = re.search(r"(?m)^package\s+([\w.]+);", src)
    if not pkg:
        return None
    class_reason = None
    decl = CLASS_DECL.search(src)
    head = src[:decl.end()] if decl else ""
    if "@AllowUnlockedSchedule" in head:
        class_reason = allow_reason(head)
    methods = []  # (locked, reason or None)
    prev_end = 0
    for m in SCHEDULED.finditer(src):
        pos = m.end()
        if pos < len(src) and src[pos] == "(":
            pos = find_close(src, pos, "(", ")")
        brace = src.find("{", pos)
        if brace < 0:
            continue
        body_end = find_close(src, brace, "{", "}")
        body = src[brace:body_end + 1]
        # annotations of this method: from the end of the previous scheduled body to its opening brace
        window = src[prev_end:brace]
        prev_end = body_end
        # this method's annotation block starts after the last statement-ending line before it
        ends = list(re.finditer(r"(?m)^[ \t]*\}[ \t]*$|;[ \t]*$", window))
        reason = allow_reason(window[ends[-1].end():] if ends else window) or class_reason
        methods.append((LOCK_CALL in body, reason))
    if not methods:
        return None
    return {
        "fqcn": pkg.group(1) + "." + path.stem,
        "name": path.stem,
        "module": path.relative_to(CORE).parts[0],
        "methods": methods,
    }


def scan_sources():
    classes = []
    for path in sorted(CORE.glob("*/src/main/java/**/*.java")):
        if SCHEDULED.search(path.read_text(encoding="utf-8")):
            info = scan_class(path)
            if info:
                classes.append(info)
    return classes


class YamlError(Exception):
    pass


LISTS = ("skip-bean-classes", "keep-bean-classes")
MAPS = ("pools", "pool-lifecycle")  # name -> list of classes
PATTERN = re.compile(r"^[\w.$]+\*?$")
KEY = re.compile(r"([\w-]+):(?:\s+(.*))?")


def strip_comment(line):
    """Drop a trailing ' # comment' that is outside quotes."""
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i].rstrip()
    if quote:
        raise YamlError("unterminated quote: %r" % line)
    return line.rstrip()


def scalar(text):
    t = text.strip()
    if len(t) >= 2 and t[0] in "\"'":
        if t[-1] != t[0]:
            raise YamlError("cannot parse quoted item %r" % text)
        t = t[1:-1]
    if not PATTERN.match(t):
        raise YamlError("%r is not a class name or 'package.*' prefix" % t)
    return t


def inline_items(value):
    """Items of a flow list '[a, b]' or a comma-separated scalar 'a,b', as Spring's Binder accepts them."""
    v = value.strip()
    if v.startswith("["):
        if not v.endswith("]"):
            raise YamlError("multi-line or unterminated flow list: %r" % value)
        v = v[1:-1]
    elif v[0] in "{&*!|>%@`":
        raise YamlError("unsupported YAML value %r" % value)
    return [scalar(p) for p in v.split(",") if p.strip()]


def load_lists(path):
    """skip/keep bean class lists and the pool maps of the app.scheduling block of one profile file.

    Understands the subset these profile files use (block list, flow list, comma string, quoted items,
    trailing comments, one level of name -> list maps for `pools` / `pool-lifecycle`) and exits instead of
    guessing on anything else under app.scheduling, so a profile edit can never silently turn into an empty list.
    """
    try:
        return parse_lists(path.read_text(encoding="utf-8").splitlines())
    except YamlError as e:
        sys.exit("%s: %s" % (path.name, e))


def parse_lists(lines):
    out = {k: [] for k in LISTS}
    out.update({k: {} for k in MAPS})
    seen = set()
    in_app = in_sched = False
    sched_indent = key_indent = None  # indent of the scheduling: line / of the keys under it
    key = sub = sub_indent = None  # current key under scheduling / current name inside a MAPS key
    for n, raw in enumerate(lines, 1):
        if "\t" in raw[:len(raw) - len(raw.lstrip())]:
            raise YamlError("line %d: tab indentation" % n)
        line = strip_comment(raw)
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip())
        s = line.strip()
        if indent == 0:
            in_app, in_sched = s == "app:", False
            continue
        if in_sched and indent <= sched_indent:
            in_sched = False
        if in_app and not in_sched and s == "scheduling:":
            if "scheduling" in seen:
                raise YamlError("line %d: scheduling: appears twice" % n)
            seen.add("scheduling")
            in_sched, sched_indent, key, key_indent = True, indent, None, None
            continue
        if not in_sched:
            continue
        if key_indent is None:
            key_indent = indent
        if indent == key_indent:
            m = KEY.fullmatch(s)
            if not m:
                raise YamlError("line %d: cannot classify %r under app.scheduling" % (n, s))
            key, sub, sub_indent = m.group(1), None, None
            if key in LISTS + MAPS:
                if key in seen:
                    raise YamlError("line %d: %s appears twice" % (n, key))
                seen.add(key)
                if m.group(2):
                    if key in MAPS:
                        raise YamlError("line %d: %s must be a map of name -> list" % (n, key))
                    out[key].extend(inline_items(m.group(2)))
        elif indent > key_indent and key is not None:
            if key in LISTS:
                if not s.startswith("- "):
                    raise YamlError("line %d: expected a '- item' under %s, got %r" % (n, key, s))
                out[key].append(scalar(s[2:]))
            elif key in MAPS:
                if sub_indent is None:
                    sub_indent = indent
                if indent == sub_indent:
                    m = KEY.fullmatch(s)
                    if not m:
                        raise YamlError("line %d: expected '<name>:' under %s, got %r" % (n, key, s))
                    sub = m.group(1)
                    if sub in out[key]:
                        raise YamlError("line %d: %s.%s appears twice" % (n, key, sub))
                    out[key][sub] = inline_items(m.group(2)) if m.group(2) else []
                elif indent > sub_indent and sub is not None:
                    if not s.startswith("- "):
                        raise YamlError("line %d: expected a '- item' under %s.%s, got %r" % (n, key, sub, s))
                    out[key][sub].append(scalar(s[2:]))
                else:
                    raise YamlError("line %d: cannot classify %r under %s" % (n, s, key))
            # else: the body of a key this generator does not read
        else:
            raise YamlError("line %d: cannot classify %r under app.scheduling" % (n, s))
    if "scheduling" not in seen:
        raise YamlError("no app.scheduling block found")
    return out


def matches(fqcn, patterns):
    # same rule as SchedulingRoleMatcher: exact name, or a prefix ending in '*'
    return any(fqcn == p or (p.endswith("*") and fqcn.startswith(p[:-1])) for p in patterns)


def runs(fqcn, lists):
    """Whether a profile's lists leave the bean registered (a keep entry beats a skip pattern)."""
    if matches(fqcn, lists["keep-bean-classes"]):
        return True
    return not matches(fqcn, lists["skip-bean-classes"])


def yn(v):
    return "yes" if v else "-"


def label(c):
    return "`%s`" % c["name"]


# Per-class notes for the "Cross-replica" column that no annotation carries. Each entry names a string that must
# still occur in the class's source, so the note cannot outlive the behaviour it describes.
LOCK_NOTES = {
    "com.agenticai.core.knowledge.KnowledgeIngestionJobRunner": (
        "app.knowledge.ingestion.claiming.enabled",
        "advisory lock, or per-job claiming when `KNOWLEDGE_INGESTION_CLAIMING_ENABLED`"),
}
SHAPES = ("api", "jobs")
HEAD = ("| Class | Module | API shape | Main jobs pod | Own pool, when enabled | Cross-replica |\n"
        "|---|---|---|---|---|---|\n")


def lock_note(c, allowed):
    note = LOCK_NOTES.get(c["fqcn"])
    if note is None:
        base = "advisory lock"
    else:
        needle, base = note
        path = next(CORE.glob("*/src/main/java/%s.java" % c["fqcn"].replace(".", "/")), None)
        if path is None or needle not in path.read_text(encoding="utf-8"):
            sys.exit("%s: the lock note expects %r in the source, which is gone; update LOCK_NOTES" % (c["fqcn"], needle))
    return base if not allowed else base + " (+ %d per-JVM method(s), see source)" % len(allowed)


def pool_of(fqcn, pools, name):
    owners = [p for p, patterns in pools.items() if matches(fqcn, patterns)]
    if len(owners) > 1:
        sys.exit("%s is listed in more than one pool: %s" % (name, ", ".join(owners)))
    return owners[0] if owners else None


def names(items):
    return ", ".join(items[:-1]) + " and " + items[-1] if len(items) > 1 else items[0]


def render(classes):
    shapes = {n: load_lists(CFG / ("application-shape-%s.yml" % n)) for n in SHAPES}
    for n, lists in shapes.items():
        if not (lists["skip-bean-classes"] or lists["keep-bean-classes"]):
            sys.exit("application-shape-%s.yml: parsed no skip/keep bean classes; refusing to render" % n)
    pools = shapes["jobs"]["pools"]
    lifecycle = shapes["jobs"]["pool-lifecycle"]
    if not pools:
        sys.exit("application-shape-jobs.yml: parsed no app.scheduling.pools; refusing to render")
    for pool, patterns in list(pools.items()) + list(lifecycle.items()):
        if pool not in pools:
            sys.exit("application-shape-jobs.yml: pool-lifecycle names pool %r, which is not in pools" % pool)
        for p in patterns:
            if not any(matches(c["fqcn"], [p]) for c in classes):
                sys.exit("application-shape-jobs.yml: %s lists %s, which is not a class with a @Scheduled method"
                         % (pool, p))
    owned, also, jvm = [], [], []
    also_locked = 0
    perjvm_fqcn = {}
    for c in sorted(classes, key=lambda c: c["name"]):
        locked = [m for m in c["methods"] if m[0]]
        allowed = [m for m in c["methods"] if not m[0] and m[1]]
        if any(not m[0] and not m[1] for m in c["methods"]):
            sys.exit("%s has a @Scheduled method that is neither locked nor @AllowUnlockedSchedule" % c["fqcn"])
        if allowed and not locked:
            jvm.append("| %s | %s | %s |" % (label(c), c["module"], allowed[0][1]))
            perjvm_fqcn[c["fqcn"]] = c["name"]
            continue
        f = c["fqcn"]
        api = runs(f, shapes["api"])
        single = runs(f, shapes["jobs"])
        pool = pool_of(f, pools, c["name"])
        main_pod = "-" if not single else ("yes" if pool is None else "yes, unless its pool is on")
        row = "| %s | %s | %s | %s | %s | %s |" % (
            label(c), c["module"], yn(api), main_pod, pool or "-", lock_note(c, allowed))
        (also if api else owned).append(row)
        if api and not allowed:
            also_locked += 1
    for pool, patterns in lifecycle.items():
        for p in patterns:
            if not any(matches(f, [p]) for f in perjvm_fqcn):
                sys.exit("application-shape-jobs.yml: pool-lifecycle.%s lists %s, which is not a per-JVM class" % (pool, p))
    keeps = {pool: {n for f, n in perjvm_fqcn.items() if any(matches(f, [p]) for p in patterns)}
             for pool, patterns in lifecycle.items()}
    bare = ["`%s`" % p for p in pools if p not in keeps]
    sentences = []
    if bare:
        sentences.append("The %s %s all of these: their jobs hold none of this state."
                         % (names(bare), "pool skips" if len(bare) == 1 else "pools skip"))
    for pool, kept in keeps.items():
        left = sorted(set(perjvm_fqcn.values()) - kept)
        sentences.append("The `%s` pool keeps %d of them (%s)." % (
            pool, len(kept), "every row except " + names(["`%s`" % n for n in left]) if left else "every row"))
    return {
        "alsolocked": ("All %d" % len(also)) if also_locked == len(also) else "%d of %d" % (also_locked, len(also)),
        "owned": HEAD + "\n".join(owned),
        "also": HEAD + "\n".join(also),
        "perjvm": "| Class | Module | Why every replica runs it |\n|---|---|---|\n" + "\n".join(jvm),
        "perjvmpools": " ".join(sentences),
        "poolnames": list(pools),
    }


INLINE = ("alsolocked", "perjvmpools")  # regions that sit inside a sentence rather than on their own lines


def splice(doc, regions):
    for name in regions:
        if name == "poolnames":
            for pool in regions[name]:  # the hand-written shape table must have a row for every pool
                if not re.search(r"\*\*Jobs, `%s` pool\*\*" % re.escape(pool), doc):
                    sys.exit("%s: the shapes table has no '**Jobs, `%s` pool**' row" % (DOC, pool))
            continue
        body = regions[name]
        b, e = BEGIN.format(name), END.format(name)
        i, j = doc.find(b), doc.find(e)
        if i < 0 or j < i:
            sys.exit("%s: missing markers %s / %s" % (DOC, b, e))
        sep = "" if name in INLINE else "\n"
        doc = doc[:i + len(b)] + sep + body + sep + doc[j:]
    return doc


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--check", action="store_true", help="fail if the doc differs from the generated output")
    ap.add_argument("--print", action="store_true", help="print the regions instead of writing")
    args = ap.parse_args()
    regions = render(scan_sources())
    if args.print:
        for k, v in regions.items():
            if k != "poolnames":
                print("## %s\n%s\n" % (k, v))
        return 0
    current = DOC.read_text(encoding="utf-8")
    new = splice(current, regions)
    if args.check:
        if new != current:
            sys.stderr.write("DEPLOYMENT-SHAPES.md is stale. Run: python3 scripts/docs/gen-shapes.py\n")
            return 1
        print("DEPLOYMENT-SHAPES.md ownership tables are up to date")
        return 0
    if new != current:
        DOC.write_text(new, encoding="utf-8")
        print("updated", DOC.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
