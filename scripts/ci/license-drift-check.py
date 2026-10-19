#!/usr/bin/env python3
"""Gate a ScanCode report against an SPDX allowlist.

Collects every license expression ScanCode detected in the scanned files,
splits compound expressions into atomic license ids (the operators AND / OR /
WITH and parentheses are dropped), and fails when any id is not in the
allowlist file. Atomic checking is deliberate: "mit OR gpl-3.0" would pass a
whole-expression check on the MIT alternative but still means a maintainer has
to choose and document that choice, so it is flagged for review instead.

Usage: license-drift-check.py <scancode.json> <allowlist.txt>
"""
import json
import re
import sys

OPERATORS = {"and", "or", "with"}


def load_allowlist(path):
    allowed = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                allowed.add(line)
    return allowed


def atomic_ids(expression):
    return {
        token
        for token in re.split(r"[()\s]+", expression)
        if token and token.lower() not in OPERATORS
    }


def expressions(resource):
    found = set()
    for section in ("license_detections", "license_clues"):
        for det in resource.get(section) or []:
            expr = det.get("license_expression_spdx") or det.get(
                "license_expression"
            )
            if expr:
                found.add(expr)
    return found


def main():
    if len(sys.argv) != 3:
        sys.exit(
            f"usage: {sys.argv[0]} <scancode.json> <allowlist.txt>"
        )
    report_path, allowlist_path = sys.argv[1], sys.argv[2]
    allowed = load_allowlist(allowlist_path)
    with open(report_path, encoding="utf-8") as fh:
        data = json.load(fh)

    clean = 0
    undetected = []
    violations = {}
    for res in data.get("files", []):
        if res.get("type") != "file":
            continue
        exprs = expressions(res)
        if not exprs:
            undetected.append(res["path"])
            continue
        bad = set()
        for expr in exprs:
            bad |= atomic_ids(expr) - allowed
        if bad:
            violations[res["path"]] = bad
        else:
            clean += 1

    print(
        f"license-drift: {clean} file(s) allowlisted, "
        f"{len(undetected)} with no detection, "
        f"{len(violations)} with unlisted license ids"
    )
    if undetected:
        print("no license detected (review only, not a failure):")
        for path in undetected[:50]:
            print(f"  {path}")
    if violations:
        print("unlisted license ids:", file=sys.stderr)
        for path, ids in sorted(violations.items()):
            print(f"  {path}: {', '.join(sorted(ids))}", file=sys.stderr)
        print(
            f"allowlist: {allowlist_path} -- add an id only once its license "
            "text is in LICENSES/ and the attribution in NOTICE",
            file=sys.stderr,
        )
        sys.exit(1)


if __name__ == "__main__":
    main()
