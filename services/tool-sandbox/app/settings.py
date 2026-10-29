"""Configuration for the tool-sandbox service.

Everything is environment-driven and every security-relevant default is the
*safe* one, so a misconfigured deployment fails closed rather than quietly
executing tenant code with fewer controls than intended. In particular
``TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP`` defaults to true: if this process is
root and cannot drop to the unprivileged uid, execution is refused outright
instead of running tenant code as root.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_uid(name: str) -> int | None:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def parse_uid_range(raw: str | None) -> tuple[int, int] | None:
    """Parse ``"20000-29999"`` into an inclusive (first, last) pair.

    Anything malformed, empty, reversed, or touching uid 0 yields ``None`` -
    which makes the runner fall back to the single shared run uid and log a
    warning, rather than guess at a range.
    """
    if raw is None or not raw.strip():
        return None
    first_s, sep, last_s = raw.strip().partition("-")
    if not sep:
        return None
    try:
        first, last = int(first_s), int(last_s)
    except ValueError:
        return None
    if first <= 0 or last < first or last > 2**31 - 2:
        return None
    return first, last


@dataclass
class Settings:
    # --- auth -----------------------------------------------------------
    #: Shared secret core sends as X-Internal-Token. Empty means the service
    #: refuses every request - there is deliberately no "no auth" mode.
    internal_token: str = field(
        default_factory=lambda: os.environ.get("TOOL_SANDBOX_INTERNAL_TOKEN", "")
    )

    # --- filesystem layout ----------------------------------------------
    # The /tmp defaults exist only for running outside the image (tests, a dev
    # laptop). The image sets TOOL_SANDBOX_WORK_ROOT=/var/sandbox/work and
    # TOOL_SANDBOX_CACHE_ROOT=/var/sandbox/cache (root-owned, 0711/0755), and
    # every run gets its own tempfile.mkdtemp() subdirectory (0700, random name)
    # under a per-tenant hashed directory - see isolation.Workdir - so no
    # predictable /tmp path is ever used for tenant data even in dev.
    work_root: Path = field(
        default_factory=lambda: Path(
            os.environ.get(
                "TOOL_SANDBOX_WORK_ROOT",
                # B108: dev-only default; the image overrides it and runs use mkdtemp.
                "/tmp/sandbox-work",  # nosec B108
            )
        )
    )
    cache_root: Path = field(
        default_factory=lambda: Path(
            os.environ.get(
                "TOOL_SANDBOX_CACHE_ROOT",
                # B108: dev-only default; the image overrides it.
                "/tmp/sandbox-cache",  # nosec B108
            )
        )
    )
    harness_root: Path = field(
        default_factory=lambda: Path(
            os.environ.get("TOOL_SANDBOX_HARNESS_ROOT", "/opt/tool-sandbox")
        )
    )

    # --- privilege / isolation ------------------------------------------
    run_uid: int | None = field(default_factory=lambda: _env_uid("TOOL_SANDBOX_RUN_UID"))
    run_gid: int | None = field(default_factory=lambda: _env_uid("TOOL_SANDBOX_RUN_GID"))
    require_privilege_drop: bool = field(
        default_factory=lambda: _env_bool("TOOL_SANDBOX_REQUIRE_PRIVILEGE_DROP", True)
    )
    #: Explicit escape hatch for the startup CAP_KILL self-check
    #: (proc.verify_kill_capability). Default false: without CAP_KILL a timed-out
    #: tool running as the dropped uid cannot be killed, so the service refuses
    #: to start. Setting this starts it anyway with degraded enforcement.
    allow_missing_cap_kill: bool = field(
        default_factory=lambda: _env_bool("TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL", False)
    )
    #: SEC-003: a dedicated uid per execution, allocated from this inclusive
    #: range (``TOOL_SANDBOX_RUN_UID_RANGE=20000-29999``). Two concurrent runs -
    #: of the same tenant or different ones - then never share a uid, so one
    #: cannot signal, ptrace, or read /proc/<pid>/environ (the VEGA_SECRET_*
    #: variables) of the other, and every process of the run's uid can be
    #: killed after it ends without touching anyone else's run. Unset or
    #: malformed falls back to the single TOOL_SANDBOX_RUN_UID with a logged
    #: warning. gid = uid; neither needs a passwd/group entry.
    run_uid_range: tuple[int, int] | None = field(
        default_factory=lambda: parse_uid_range(os.environ.get("TOOL_SANDBOX_RUN_UID_RANGE"))
    )
    #: Seccomp socket denial, default ON (SEC-003). Only takes effect when the
    #: optional ``pyseccomp`` binding is importable in the image (proc.py) - it
    #: is not in requirements today, so /health reports "unavailable" and this
    #: is a no-op until it is added. Note it is the one control that uses a
    #: ``preexec_fn`` (see proc.py's module docstring for that trade-off).
    enable_seccomp: bool = field(default_factory=lambda: _env_bool("TOOL_SANDBOX_SECCOMP", True))

    # --- budgets ---------------------------------------------------------
    max_source_bytes: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_SOURCE_BYTES", 65536)
    )
    max_input_bytes: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_INPUT_BYTES", 262144)
    )
    max_output_bytes: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_OUTPUT_BYTES", 65536)
    )
    max_result_bytes: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_RESULT_BYTES", 262144)
    )

    #: Vault secrets handed to one execution. A cap on both count and size
    #: because these become environment variables on the child process, and an
    #: unbounded environment is a way to make execve fail in a confusing place
    #: rather than a useful control. 32 named credentials is already far past
    #: what any single tool legitimately needs.
    max_secrets: int = field(default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_SECRETS", 32))
    max_secret_bytes: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_SECRET_BYTES", 8192)
    )

    default_timeout_ms: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_DEFAULT_TIMEOUT_MS", 5000)
    )
    max_timeout_ms: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_TIMEOUT_MS", 15000)
    )
    default_memory_mb: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_DEFAULT_MEMORY_MB", 128)
    )
    max_memory_mb: int = field(default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_MEMORY_MB", 512))

    #: Compilation gets its own, larger budget - javac on a cold JVM is slower
    #: than most tools will ever be, and a compile timeout reads to the user as
    #: "the platform is broken" rather than "your code is slow".
    compile_timeout_ms: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_COMPILE_TIMEOUT_MS", 30000)
    )
    compile_memory_mb: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_COMPILE_MEMORY_MB", 512)
    )

    #: Concurrent executions. Bounds total load and keeps the shared-uid
    #: RLIMIT_NPROC budget from being exhausted by parallel runs.
    max_concurrent_executions: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_CONCURRENT", 4)
    )

    #: Compiled-artifact cache entries per tenant, evicted oldest-first.
    max_cache_entries_per_tenant: int = field(
        default_factory=lambda: _env_int("TOOL_SANDBOX_MAX_CACHE_ENTRIES", 64)
    )

    # --- toolchain -------------------------------------------------------
    javac: str = field(default_factory=lambda: os.environ.get("TOOL_SANDBOX_JAVAC", "javac"))
    java: str = field(default_factory=lambda: os.environ.get("TOOL_SANDBOX_JAVA", "java"))
    python: str = field(default_factory=lambda: os.environ.get("TOOL_SANDBOX_PYTHON", "python3"))
    node: str = field(default_factory=lambda: os.environ.get("TOOL_SANDBOX_NODE", "node"))

    @property
    def vega_api_jar(self) -> Path:
        return self.harness_root / "lib" / "vega-api.jar"

    @property
    def harness_jar(self) -> Path:
        return self.harness_root / "lib" / "harness.jar"

    @property
    def py_harness(self) -> Path:
        return self.harness_root / "harness" / "py_harness.py"

    def clamp_timeout_ms(self, requested: int | None) -> int:
        value = self.default_timeout_ms if not requested else requested
        return max(100, min(value, self.max_timeout_ms))

    def clamp_memory_mb(self, requested: int | None) -> int:
        value = self.default_memory_mb if not requested else requested
        return max(32, min(value, self.max_memory_mb))


settings = Settings()
