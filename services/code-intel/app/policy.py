"""Guardrail / policy engine — permissions as configuration, not prompts.

Design follows the 2026 consensus for safe autonomous code agents:

- **Permission as infrastructure** (OpenAI Agents SDK, Codex requirements.toml,
  Claude Code's gate precedence): rules live in a config object you can test
  and diff, never only in a system prompt.
- **Six-gate precedence** (deny → ask → allow → mode), evaluated in a fixed
  order so a deny can never be overridden by a later allow.
- **Least agency**: read/search/graph are safe; running commands and writing
  files are privileged and separately gated.
- **Auto-approve only non-destructive ops** (ericmjl): read/test commands may
  auto-allow; anything that mutates state escalates to a human.
- **Reversibility + blast-radius limits**: writes confined to the workspace,
  file count/size caps, command timeout caps.
- **Plan-then-apply**: a dry-run decision path so an agent proposes before it
  acts.
- **Prompt-injection canary**: log (not block) suspicious instruction-like
  strings in inputs.

The OS/container sandbox (see executor + deploy notes) is the *enforcement*
layer; this module is the *policy* layer. Both are needed — this decides
"allowed?", the sandbox guarantees "can't escape even if a bug says yes".
"""

from __future__ import annotations

import re
import shlex
from dataclasses import dataclass, field
from enum import Enum


class Decision(str, Enum):
    ALLOW = "allow"          # proceed without a human
    ASK = "ask"              # requires human approval first
    DENY = "deny"            # never allowed


@dataclass
class PolicyResult:
    decision: Decision
    reason: str
    rule: str                # which rule matched, for the audit trail
    canary: list[str] = field(default_factory=list)  # injection signals seen


# ---- default rule sets (overridable per tenant/repo) --------------------

# Read-only / analysis commands that are safe to auto-allow (ericmjl's list,
# extended for the JVM/.NET/Node build tools this platform's tenants use).
DEFAULT_ALLOW_COMMAND_PREFIXES = (
    "ls", "cat", "head", "tail", "grep", "rg", "find", "wc", "file", "stat",
    "echo", "pwd", "tree", "which", "env",
    "git status", "git log", "git diff", "git show", "git blame",
    "git branch", "git rev-parse",
    "pytest", "python -m pytest", "mypy", "ruff check", "flake8", "black --check",
    "npm test", "npm run test", "npm run lint", "yarn test", "pnpm test",
    "mvn -q test", "mvn test", "mvn -q verify", "mvn compile", "./mvnw test",
    "gradle test", "./gradlew test", "gradle check",
    "dotnet test", "dotnet build",
    "go test", "go vet", "go build",
    "cargo test", "cargo check", "cargo clippy",
)

# Hard-deny substrings/patterns — never runnable regardless of other rules.
DEFAULT_DENY_PATTERNS = (
    r"\brm\s+-rf\b", r"\brm\s+-fr\b", r":\(\)\s*\{",   # fork bomb
    r"\bsudo\b", r"\bchmod\s+777\b", r"\bmkfs\b", r"\bdd\s+if=",
    r">\s*/dev/sd", r"\bshutdown\b", r"\breboot\b",
    r"\bgit\s+push\b", r"\bgit\s+reset\s+--hard\b",   # writes escape the workspace's value
    r"\bcurl\b.*\|\s*(ba)?sh", r"\bwget\b.*\|\s*(ba)?sh",  # curl|sh
    r"\bnpm\s+publish\b", r"\bpip\s+install\b.*--index-url",
    r"\b(aws|gcloud|az)\s+", r"\bkubectl\b", r"\bterraform\s+apply\b",
    r"\bssh\b", r"\bscp\b",
    r"\.\./", r"/etc/", r"~/.ssh", r"id_rsa", r"\.aws/credentials",
)

# Supply-chain: agents must not float versions (Trivy/axios class incidents).
DEFAULT_DENY_PATTERNS_SUPPLY_CHAIN = (
    r"--latest\b", r"install\s+.*@latest",
)

# Prompt-injection canary — log, do not auto-block (false positives hurt UX).
INJECTION_CANARY_PATTERNS = (
    re.compile(r"ignore (?:the )?(?:previous|all|above) (?:instructions|rules)", re.I),
    re.compile(r"disregard (?:your|the) (?:system )?prompt", re.I),
    re.compile(r"<\|im_(?:start|end)\|>"),
    re.compile(r"\bexfiltrat", re.I),
    re.compile(r"reveal (?:your )?(?:system )?prompt", re.I),
)


@dataclass
class Policy:
    """Per-tenant/repo guardrail configuration. Serializable, diffable."""
    # Master switches
    exec_enabled: bool = False           # run_code allowed at all?
    apply_enabled: bool = True           # apply_files allowed at all?
    # Autonomy: how much may proceed WITHOUT a human approval
    #   "read_only"  -> only auto-allow-listed (non-mutating) commands run;
    #                   everything mutating -> ASK
    #   "test_gated" -> allow-listed commands auto-run; apply_files -> ASK
    #   "autopilot"  -> allow-listed auto-run; non-listed commands -> ASK;
    #                   apply auto-allowed (still sandboxed + capped)
    mode: str = "test_gated"
    allow_command_prefixes: tuple = DEFAULT_ALLOW_COMMAND_PREFIXES
    deny_patterns: tuple = DEFAULT_DENY_PATTERNS + DEFAULT_DENY_PATTERNS_SUPPLY_CHAIN
    # Blast-radius caps
    max_files_per_apply: int = 25
    max_bytes_per_file: int = 512_000
    max_command_timeout: int = 600
    # Write-path scoping: apply_files may only touch these path prefixes
    # (empty = anywhere inside the workspace). e.g. ["src/", "test/"].
    writable_path_prefixes: tuple = ()
    # Protected paths agents may never write, even inside the workspace.
    protected_path_globs: tuple = (
        ".git/", "Dockerfile", ".github/", "deploy/", "**/secrets*",
        "**/*.pem", "**/*.key", ".env", "**/.env*",
    )

    def to_dict(self) -> dict:
        return {
            "exec_enabled": self.exec_enabled,
            "apply_enabled": self.apply_enabled,
            "mode": self.mode,
            "max_files_per_apply": self.max_files_per_apply,
            "max_bytes_per_file": self.max_bytes_per_file,
            "max_command_timeout": self.max_command_timeout,
            "writable_path_prefixes": list(self.writable_path_prefixes),
            "protected_path_globs": list(self.protected_path_globs),
        }

    @classmethod
    def from_dict(cls, d: dict | None) -> Policy:
        d = d or {}
        known = {f for f in cls.__dataclass_fields__}  # type: ignore[attr-defined]  # noqa: C416
        kw = {k: v for k, v in d.items() if k in known}
        for seq in ("writable_path_prefixes", "protected_path_globs"):
            if seq in kw and isinstance(kw[seq], list):
                kw[seq] = tuple(kw[seq])
        return cls(**kw)


# ---- evaluation ---------------------------------------------------------

def scan_canary(*texts: str) -> list[str]:
    hits = []
    for t in texts:
        if not t:
            continue
        for rx in INJECTION_CANARY_PATTERNS:
            if rx.search(t):
                hits.append(rx.pattern)
    return hits


def _command_head(command: str) -> str:
    try:
        parts = shlex.split(command)
    except ValueError:
        parts = command.split()
    return " ".join(parts[:3]) if parts else ""


def evaluate_command(policy: Policy, command: str) -> PolicyResult:
    """Six-gate order: deny -> (canary) -> allow-list -> mode. A DENY can
    never be overridden by a later ALLOW."""
    canary = scan_canary(command)

    if not policy.exec_enabled:
        return PolicyResult(Decision.DENY,
                            "command execution is disabled by policy",
                            "exec_disabled", canary)

    # Gate 1: hard deny (highest precedence)
    for pat in policy.deny_patterns:
        if re.search(pat, command):
            return PolicyResult(Decision.DENY,
                                f"command matches deny rule: {pat}",
                                "deny_pattern", canary)

    # Gate 2: allow-list (non-destructive) -> auto-allow
    head = command.strip()
    for prefix in policy.allow_command_prefixes:
        if head == prefix or head.startswith(prefix + " ") or head.startswith(prefix + "\t"):
            return PolicyResult(Decision.ALLOW,
                                f"matches allow-listed prefix: {prefix!r}",
                                "allow_prefix", canary)

    # Gate 3: mode decides the rest
    if policy.mode == "autopilot":
        return PolicyResult(Decision.ASK if canary else Decision.ALLOW,
                            "non-listed command; autopilot proceeds"
                            + (" but canary flagged" if canary else ""),
                            "mode_autopilot", canary)
    # read_only / test_gated: unknown commands need a human
    return PolicyResult(Decision.ASK,
                        f"command '{_command_head(command)}…' is not on the "
                        "auto-allow list; human approval required",
                        "mode_ask", canary)


def _matches_glob(path: str, glob: str) -> bool:
    import fnmatch
    p = path.replace("\\", "/")
    g = glob.replace("\\", "/")
    if g.endswith("/"):
        return p == g[:-1] or p.startswith(g)
    return fnmatch.fnmatch(p, g) or (("/" not in g) and fnmatch.fnmatch(p.split("/")[-1], g))


def evaluate_apply(policy: Policy, files: list[dict]) -> PolicyResult:
    canary = scan_canary(*[f.get("content", "") for f in files],
                         *[f.get("path", "") for f in files])

    if not policy.apply_enabled:
        return PolicyResult(Decision.DENY,
                            "file writes are disabled by policy",
                            "apply_disabled", canary)

    if len(files) > policy.max_files_per_apply:
        return PolicyResult(Decision.DENY,
                            f"{len(files)} files exceeds cap "
                            f"({policy.max_files_per_apply})",
                            "max_files", canary)

    for f in files:
        path = (f.get("path") or "").replace("\\", "/")
        content = f.get("content", "") or ""
        if len(content.encode("utf-8")) > policy.max_bytes_per_file:
            return PolicyResult(Decision.DENY,
                                f"{path}: file exceeds "
                                f"{policy.max_bytes_per_file} bytes",
                                "max_bytes", canary)
        # protected paths -> hard deny
        for glob in policy.protected_path_globs:
            if _matches_glob(path, glob):
                return PolicyResult(Decision.DENY,
                                    f"{path}: matches protected path {glob!r}",
                                    "protected_path", canary)
        # write-scope allow-list (if configured)
        if policy.writable_path_prefixes:
            if not any(path.startswith(pfx) for pfx in policy.writable_path_prefixes):
                return PolicyResult(Decision.DENY,
                                    f"{path}: outside writable scope "
                                    f"{policy.writable_path_prefixes}",
                                    "write_scope", canary)

    # Within caps + scope: mode decides whether a human must approve.
    if policy.mode == "autopilot":
        return PolicyResult(Decision.ASK if canary else Decision.ALLOW,
                            "apply within caps; autopilot proceeds"
                            + (" but canary flagged" if canary else ""),
                            "mode_autopilot", canary)
    return PolicyResult(Decision.ASK,
                        "file writes require human approval in this mode",
                        "mode_ask", canary)


def clamp_timeout(policy: Policy, timeout: int | None, default: int) -> int:
    t = timeout or default
    return max(1, min(t, policy.max_command_timeout))
