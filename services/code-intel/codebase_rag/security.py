"""Offline, deterministic security scan: hard-coded secrets, dangerous
sinks, and a dependency inventory. No network, no LLM.

THIS IS A HEURISTIC SCANNER. It is line-oriented pattern matching with
placeholder/entropy guards; it does no data-flow or taint analysis, so it
has false positives AND false negatives. It does not replace CodeQL/Semgrep
(code), gitleaks/trufflehog (secrets, history) or Trivy/OSV/Dependabot (CVEs).
Dependency output is an inventory only: matching it against vulnerabilities
needs an advisory feed, which is out of scope here; pass an
`advisory_provider` (see security_deps.AdvisoryProvider) to plug one in.

Tenant isolation is the same as every other analysis: the caller hands in a
Config rooted at the tenant's own checkout (service._handles), and every read
goes through discovery.read_text -> safe_path (no symlinks, realpath confined
to the root). Secret values are redacted in every snippet; full values are
never returned.

Suppression: an inline `vegaduta:ignore[rule,...]` / `nosec` /
`pragma: allowlist secret` / `gitleaks:allow` comment on the line, or a
`.vegaduta-security-ignore` file at the repo root (one `glob` or
`rule-id:glob` per line, `#` comments). Test and fixture paths are skipped
unless include_tests=True.
"""

from __future__ import annotations

import fnmatch
import os
import re

from . import discovery, gitstate
from .config import Config
from .graph_extract import is_test_path
from .security_deps import AdvisoryProvider, is_manifest, parse_manifest
from .security_rules import (
    SEVERITY_ORDER,
    SINK_RULES,
    find_secrets,
    is_comment_line,
    line_suppressed,
    redact_line,
    sql_context,
)

DISCLAIMER = (
    "Heuristic scanner (regex + entropy guards, no data-flow analysis): expect false "
    "positives and misses. Not a replacement for CodeQL/Semgrep, gitleaks/trufflehog "
    "or Trivy/OSV. Dependencies are an inventory only; CVE matching needs an "
    "advisory feed and is not performed."
)
IGNORE_FILE = ".vegaduta-security-ignore"
_IGNORE_LINE_RX = re.compile(r"^([a-z][a-z0-9\-]*):(.+)$")
CATEGORIES = ("secrets", "sinks", "dependencies")

MAX_FILES = 5000
MAX_FILE_BYTES = 1024 * 1024
MAX_LINE_CHARS = 2000  # longer lines are minified/generated: skipped
_SKIP_DIRS = {
    ".git", ".hg", ".svn", ".coderag", "node_modules", "vendor", "dist", "build", "out",
    "target", ".venv", "venv", "__pycache__", ".tox", ".gradle", ".next", ".nuxt",
    "coverage", "site-packages",
}  # fmt: skip
_LANG_BY_EXT = {
    ".py": "python",
    ".js": "javascript", ".jsx": "javascript", ".mjs": "javascript", ".cjs": "javascript",
    ".ts": "typescript", ".tsx": "typescript", ".mts": "typescript", ".cts": "typescript",
    ".java": "java",
}  # fmt: skip
# Secrets can live in config as well as code; scan these too (secrets only).
_SECRET_ONLY_EXT = {
    ".env", ".yml", ".yaml", ".json", ".toml", ".ini", ".cfg", ".conf", ".properties",
    ".xml", ".sh", ".tf", ".pem", ".key", ".txt", ".go", ".rb", ".php", ".cs", ".kt",
    ".rs", ".gradle",
}  # fmt: skip
_FIXTURE_DIRS = {
    "fixtures",
    "fixture",
    "__fixtures__",
    "testdata",
    "test_data",
    "mocks",
    "__mocks__",
}


def _is_fixture(rel: str) -> bool:
    if is_test_path(rel):
        return True
    return any(p in _FIXTURE_DIRS for p in rel.split("/")[:-1])


def _list_files(cfg: Config) -> list[str]:
    if gitstate.is_git_repo(cfg.root):
        cands = gitstate.list_tracked_and_untracked(cfg.root)
    else:
        cands = []
        for dp, dns, fns in os.walk(cfg.root, followlinks=False):
            dns[:] = [d for d in dns if d not in _SKIP_DIRS]
            rel_dir = os.path.relpath(dp, cfg.root)
            rel_dir = "" if rel_dir == "." else rel_dir.replace("\\", "/")
            cands.extend((rel_dir + "/" + n).lstrip("/") for n in fns)
    out = []
    for rel in cands:
        rel = rel.replace("\\", "/")
        if any(p in _SKIP_DIRS for p in rel.split("/")[:-1]):
            continue
        if rel.endswith((".min.js", ".map")):
            continue
        out.append(rel)
    return sorted(set(out))[:MAX_FILES]


def _load_ignores(cfg: Config) -> list[tuple[str, str]]:
    text = discovery.read_text(cfg, IGNORE_FILE)
    rules: list[tuple[str, str]] = []
    for raw in (text or "").split("\n"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = _IGNORE_LINE_RX.match(line)
        rules.append((m.group(1), m.group(2).strip()) if m else ("", line))
    return rules


def _path_ignored(rel: str, rule_id: str, ignores: list[tuple[str, str]]) -> bool:
    return any(
        (not r or r == rule_id)
        and (fnmatch.fnmatch(rel, g) or fnmatch.fnmatch(os.path.basename(rel), g))
        for r, g in ignores
    )


def _read(cfg: Config, rel: str) -> str | None:
    p = discovery.safe_path(cfg.root, rel)
    if p is None:
        return None
    try:
        if os.lstat(p).st_size > MAX_FILE_BYTES:
            return None
    except OSError:
        return None
    text = discovery.read_text(cfg, rel)
    if text is None or "\0" in text[:8000]:
        return None
    return text


def _key_body_follows(lines: list[str], i: int) -> bool:
    """A real PEM block has base64 (or a Proc-Type header) right after BEGIN;
    a bare header in docs/regexes does not."""
    nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
    return nxt.startswith(("Proc-Type", "MI")) or (
        len(nxt) >= 40 and all(c.isalnum() or c in "+/=" for c in nxt)
    )


def _finding(rule_id, severity, category, rel, n, line, title, remediation, cwe="", conf="medium"):
    return {
        "rule_id": rule_id,
        "severity": severity,
        "category": category,
        "file": rel,
        "line": n,
        "snippet": redact_line(line),
        "title": title,
        "cwe": cwe,
        "confidence": conf,
        "remediation": remediation,
    }


def _scan_text(rel, text, lang, want_secrets, want_sinks, ignores) -> list[dict]:
    out: list[dict] = []
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if len(line) > MAX_LINE_CHARS or not line.strip():
            continue
        if want_secrets:
            for rule, rid, _val, _span in find_secrets(line):
                if line_suppressed(line, rid) or _path_ignored(rel, rid, ignores):
                    continue
                if rid == "secret-private-key" and not _key_body_follows(lines, i):
                    continue
                out.append(
                    _finding(
                        rid,
                        rule.severity if rule else "low",
                        "secret",
                        rel,
                        i + 1,
                        line,
                        rule.title if rule else "High-entropy string literal",
                        rule.remediation
                        if rule
                        else "If this is a credential, rotate it and store it in a secret manager.",
                        "CWE-798",
                        "high" if rule and rule.id != "secret-generic-assignment" else "low",
                    )
                )
        if want_sinks and lang and not is_comment_line(line):
            for r in SINK_RULES:
                if lang not in r.langs or not r.rx.search(line):
                    continue
                if r.needs_sql and not sql_context(line):
                    continue
                if line_suppressed(line, r.id) or _path_ignored(rel, r.id, ignores):
                    continue
                out.append(
                    _finding(r.id, r.severity, r.category, rel, i + 1, line, r.title,
                             r.remediation, r.cwe, r.confidence)
                )  # fmt: skip
    return out


def scan(
    cfg: Config,
    categories: tuple[str, ...] | list[str] = CATEGORIES,
    include_tests: bool = False,
    min_severity: str = "low",
    limit: int = 500,
    advisory_provider: AdvisoryProvider | None = None,
) -> dict:
    """Run the scan over cfg.root. Returns {summary, findings, dependencies,
    advisories, advisory_matching, disclaimer}. Findings are sorted by
    severity then file:line and capped at `limit` (summary counts are
    pre-cap; `truncated` says whether the cap bit)."""
    cats = {c for c in categories if c in CATEGORIES} or set(CATEGORIES)
    floor = SEVERITY_ORDER.get(min_severity, 1)
    ignores = _load_ignores(cfg)
    findings: list[dict] = []
    deps: list[dict] = []
    scanned = skipped_tests = 0
    for rel in _list_files(cfg):
        base = rel.rsplit("/", 1)[-1]
        ext = os.path.splitext(base)[1].lower()
        if not include_tests and _is_fixture(rel):
            skipped_tests += 1
            continue
        manifest = "dependencies" in cats and is_manifest(base)
        lang = _LANG_BY_EXT.get(ext)
        code_scan = bool(lang) or ext in _SECRET_ONLY_EXT or base.startswith(".env")
        if not manifest and not code_scan:
            continue
        text = _read(cfg, rel)
        if text is None:
            continue
        scanned += 1
        if manifest:
            deps.extend(parse_manifest(base, text, rel))
        if code_scan and base != "package-lock.json":
            findings.extend(
                _scan_text(
                    rel, text, lang, "secrets" in cats, "sinks" in cats and bool(lang), ignores
                )
            )
    findings = [f for f in findings if SEVERITY_ORDER[f["severity"]] >= floor]
    findings.sort(
        key=lambda f: (-SEVERITY_ORDER[f["severity"]], f["file"], f["line"], f["rule_id"])
    )
    by_sev: dict[str, int] = {}
    by_rule: dict[str, int] = {}
    for f in findings:
        by_sev[f["severity"]] = by_sev.get(f["severity"], 0) + 1
        by_rule[f["rule_id"]] = by_rule.get(f["rule_id"], 0) + 1
    deps.sort(key=lambda d: (d["ecosystem"], d["name"], d["version"], d["file"]))
    advisories: list[dict] = []
    performed = False
    if advisory_provider is not None and deps:
        advisories = list(advisory_provider(deps))
        performed = True
    cap = max(1, min(limit, 5000))
    return {
        "summary": {
            "files_scanned": scanned,
            "test_or_fixture_files_skipped": skipped_tests,
            "findings_total": len(findings),
            "by_severity": by_sev,
            "by_rule": by_rule,
            "dependencies_total": len(deps),
            "truncated": len(findings) > cap,
            "categories": sorted(cats),
        },
        "findings": findings[:cap],
        "dependencies": deps if "dependencies" in cats else [],
        "advisories": advisories,
        "advisory_matching": {
            "performed": performed,
            "note": (
                "Vulnerability (CVE/GHSA) matching is NOT performed by this scanner: it "
                "needs an advisory feed and this service is offline. Feed `dependencies` "
                "(purl + version) to OSV/Trivy, or supply an advisory_provider."
                if not performed
                else "Advisories come from the injected advisory_provider."
            ),
        },
        "disclaimer": DISCLAIMER,
    }


__all__ = ["DISCLAIMER", "scan"]
