#!/usr/bin/env python3
"""Line-coverage ratchet for the core reactor.

Reads every core/**/target/site/jacoco/jacoco.csv that `mvn -Pcoverage verify` wrote and
fails when the reactor-wide line coverage falls below the floor, or when a module that is
big enough to matter falls below the per-module floor.

  scripts/ci/coverage-gate.py [--root core] [--total 70] [--module 30] [--min-lines 500]

The floors sit a few points under today's measured values (74% overall, lowest sizeable
module 35%). Raise them as coverage improves; never lower them to make a build pass.
"""
import argparse
import csv
import glob
import os
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="core")
    ap.add_argument("--total", type=float, default=70.0)
    ap.add_argument("--module", type=float, default=30.0)
    ap.add_argument("--min-lines", type=int, default=500)
    a = ap.parse_args()

    files = glob.glob(os.path.join(a.root, "**", "target", "site", "jacoco", "jacoco.csv"), recursive=True)
    if not files:
        print("coverage-gate: no jacoco.csv found (was the build run with -Pcoverage?)", file=sys.stderr)
        return 2

    missed = covered = 0
    failures = []
    for f in sorted(files):
        m = c = 0
        with open(f, newline="") as fh:
            for row in csv.DictReader(fh):
                m += int(row["LINE_MISSED"])
                c += int(row["LINE_COVERED"])
        missed += m
        covered += c
        lines = m + c
        pct = 100.0 * c / lines if lines else 100.0
        if lines >= a.min_lines and pct < a.module:
            failures.append(f"{os.path.relpath(f, a.root).split(os.sep)[0]}: {pct:.1f}% < {a.module:.0f}% ({lines} lines)")

    total = 100.0 * covered / (missed + covered) if missed + covered else 0.0
    print(f"coverage-gate: {len(files)} modules, {missed + covered} lines, {total:.1f}% covered (floor {a.total:.0f}%)")
    if total < a.total:
        failures.append(f"reactor total {total:.1f}% < {a.total:.0f}%")
    for msg in failures:
        print(f"coverage-gate: FAIL {msg}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
