#!/usr/bin/env python3
"""Classify every ObjectProvider<T> injection point in core/*/src/main (audit finding F6).

An ObjectProvider is legitimate when the bean is optional (conditional on a property, absent in
some deployment shapes, or only used when present). It is a defect when it exists to break a
dependency cycle between two beans. This script separates the two as far as static analysis can:

  cycle-direct     the target class reaches the owner in at most two reference hops (the target
                   refers to the owner, or to a class that does). This is the likely
                   cycle-breaker group and the place to start.
  cycle-indirect   the target reaches the owner only through a longer path. Inside the large
                   agents cluster nearly everything reaches everything, so this group is mostly
                   false positives; treat it as "needs a look", not as a defect.
  conditional      the target bean is declared with @ConditionalOn* or @Profile, or the owner
                   calls getIfAvailable / ifAvailable / getIfUnique / ifUnique.
  web-only         the target is a controller; jobs and worker shapes exclude controllers.
  external         the target is not a project class (Spring, Jackson, third-party).
  unclassified     none of the above: the injection looks neither optional nor cyclic, so it is
                   probably unnecessary laziness. Start here when cleaning up.

A usage can match several groups; the order above is the precedence, so a conditional bean that
also closes a cycle is reported as a cycle candidate.

Usage:
  python3 core/tools/structure-audit/object_provider_report.py            # markdown summary
  python3 core/tools/structure-audit/object_provider_report.py --list cycle-direct
  python3 core/tools/structure-audit/object_provider_report.py --check N   # exit 1 if the
                                  # number of direct cycle candidates exceeds N (a ratchet for CI)

Standard library only; runs on Python 3.8+. Limits: reachability comes from import statements and
same-package simple names, so a reference through reflection or a fully qualified name in the body
is not seen.
"""
import argparse
import collections
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG_RE = re.compile(r"^package\s+([\w.]+);", re.M)
IMP_RE = re.compile(r"^import\s+(?:static\s+)?([\w.]+?)(?:\.\*)?;", re.M)
OP_RE = re.compile(r"ObjectProvider<\s*([\w.]+)")
IDENT_RE = re.compile(r"[A-Za-z_]\w*")
CONDITIONAL_RE = re.compile(r"@(ConditionalOn\w+|Profile)\b")
IF_AVAILABLE_RE = re.compile(r"\.(getIfAvailable|ifAvailable|getIfUnique|ifUnique)\s*\(")
CONTROLLER_RE = re.compile(r"@(Rest)?Controller\b")

CATEGORIES = ["cycle-direct", "cycle-indirect", "conditional", "web-only", "external", "unclassified"]
DIRECT_HOPS = 2


def load():
    classes = {}  # fqcn -> dict
    for mod in sorted(os.listdir(ROOT)):
        base = os.path.join(ROOT, mod, "src", "main", "java")
        if not os.path.isdir(base):
            continue
        for dp, _, files in os.walk(base):
            for f in files:
                if not f.endswith(".java"):
                    continue
                path = os.path.join(dp, f)
                with open(path, encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
                m = PKG_RE.search(text)
                if not m:
                    continue
                pkg = m.group(1)
                name = f[:-5]
                classes[pkg + "." + name] = {
                    "mod": mod,
                    "pkg": pkg,
                    "name": name,
                    "text": text,
                    "path": os.path.relpath(path, ROOT),
                }
    return classes


def resolve(fqn, classes):
    """Map an import (maybe a nested class or a member) to a project class, or None."""
    while fqn:
        if fqn in classes:
            return fqn
        if "." not in fqn:
            return None
        fqn = fqn.rsplit(".", 1)[0]
    return None


def build_graph(classes):
    by_pkg = collections.defaultdict(dict)
    for fq, c in classes.items():
        by_pkg[c["pkg"]][c["name"]] = fq
    graph = {}
    for fq, c in classes.items():
        out = set()
        for im in IMP_RE.finditer(c["text"]):
            t = resolve(im.group(1), classes)
            if t and t != fq:
                out.add(t)
        same = by_pkg[c["pkg"]]
        for ident in set(IDENT_RE.findall(c["text"])):
            t = same.get(ident)
            if t and t != fq:
                out.add(t)
        graph[fq] = out
    return graph, by_pkg


def distance(graph, start, goal, cache):
    """Fewest reference hops from start to goal, or None when goal is unreachable."""
    if start not in cache:
        dist = {start: 0}
        queue = collections.deque([start])
        while queue:
            n = queue.popleft()
            for m in graph.get(n, ()):
                if m not in dist:
                    dist[m] = dist[n] + 1
                    queue.append(m)
        cache[start] = dist
    return cache[start].get(goal)


def analyse():
    classes = load()
    graph, by_pkg = build_graph(classes)
    cache = {}
    rows = []
    for fq, c in classes.items():
        text = c["text"]
        if "ObjectProvider<" not in text:
            continue
        imports = {}
        for im in IMP_RE.finditer(text):
            full = im.group(1)
            imports[full.rsplit(".", 1)[-1]] = full
        uses_if_available = bool(IF_AVAILABLE_RE.search(text))
        for m in OP_RE.finditer(text):
            tname = m.group(1)
            target = None
            if "." in tname:
                target = resolve(tname, classes)
            elif tname in imports:
                target = resolve(imports[tname], classes)
            elif tname in by_pkg[c["pkg"]]:
                target = by_pkg[c["pkg"]][tname]
            if target is None:
                cat = "external"
            else:
                ttext = classes[target]["text"]
                hops = distance(graph, target, fq, cache)
                if hops is not None:
                    cat = "cycle-direct" if hops <= DIRECT_HOPS else "cycle-indirect"
                elif CONDITIONAL_RE.search(ttext) or uses_if_available:
                    cat = "conditional"
                elif CONTROLLER_RE.search(ttext):
                    cat = "web-only"
                else:
                    cat = "unclassified"
            rows.append((cat, c["mod"], fq, target or tname))
    return rows


def short(fq):
    return fq.replace("com.agenticai.core.", "")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", choices=CATEGORIES, help="print every usage in one category")
    ap.add_argument("--check", type=int, metavar="N", help="exit 1 if direct cycle candidates exceed N")
    args = ap.parse_args()
    rows = analyse()
    counts = collections.Counter(r[0] for r in rows)
    if args.list:
        for cat, mod, owner, target in sorted(rows):
            if cat == args.list:
                print("%-18s %s -> %s" % (mod, short(owner), short(target)))
        return 0
    if args.check is not None:
        n = counts["cycle-direct"]
        print("direct cycle candidates: %d (limit %d)" % (n, args.check))
        return 1 if n > args.check else 0
    print("ObjectProvider usages: %d in %d classes\n" % (len(rows), len({r[2] for r in rows})))
    print("| Category | Usages |\n|---|---:|")
    for cat in CATEGORIES:
        print("| %s | %d |" % (cat, counts[cat]))
    print("\nDirect cycle candidates by module:")
    by_mod = collections.Counter(r[1] for r in rows if r[0] == "cycle-direct")
    for mod, n in by_mod.most_common():
        print("  %-14s %d" % (mod, n))
    print("\nUnclassified by module:")
    by_mod = collections.Counter(r[1] for r in rows if r[0] == "unclassified")
    for mod, n in by_mod.most_common():
        print("  %-14s %d" % (mod, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
