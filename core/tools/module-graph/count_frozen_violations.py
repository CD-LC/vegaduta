#!/usr/bin/env python3
"""Count FreezingArchRule violations per rule in core/app's ArchUnit store.

ADR-0011 phase 1. ArchUnit's text store writes one violation per entry;
multi-line entries (slice / cycle violations) continue with a trailing
backslash. Usage: python core/tools/module-graph/count_frozen_violations.py
"""
import pathlib
import re

STORE = pathlib.Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources" / "archunit_store"


def rules():
    out = {}
    for line in (STORE / "stored.rules").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, file_id = line.rpartition("=")
        out[re.sub(r"\\(.)", r"\1", key)] = file_id.strip()
    return out


def count(file_id):
    n, cont = 0, False
    for line in (STORE / file_id).read_text(encoding="utf-8").splitlines():
        if not cont:
            n += 1
        cont = line.endswith("\\")
    return n


if __name__ == "__main__":
    total = 0
    for rule, fid in sorted(rules().items()):
        c = count(fid)
        total += c
        print(f"{c:6d}  {rule}")
    print(f"{total:6d}  TOTAL")
