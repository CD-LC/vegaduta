"""Hardened subprocess launch - the actual security boundary of this service.

The static checks in :mod:`classfile` and :mod:`pyvalidate` are the first
layer. This module is the layer that has to hold even if one of those is wrong,
so it does not assume anything about the code it is running.

What each control buys, and why it is done this way:

``setpriv``/``prlimit`` instead of ``preexec_fn``
    CPython's ``preexec_fn`` runs arbitrary Python between ``fork()`` and
    ``exec()``. In a threaded server (which uvicorn is) that is a documented
    deadlock hazard, because the child inherits locks other threads held at
    fork time. Wrapping the command in util-linux's ``setpriv`` and ``prlimit``
    moves the uid drop and the rlimits into small C programs that do the work
    after their own exec, so no Python runs in the forked child at all.
    ``start_new_session=True`` gives us the new session/process group without a
    ``preexec_fn`` either - it is implemented in C inside ``subprocess``.

uid drop
    Tool code runs as a dedicated unprivileged uid that owns no file in the
    image and no file this service writes, so it cannot modify the harness,
    the compiled artifact cache, or another request's working directory.
    With ``TOOL_SANDBOX_RUN_UID_RANGE`` set, that uid is allocated per
    execution (:class:`UidPool`), so concurrent runs never share one, and
    :func:`kill_uid_processes` reaps everything the run left behind -
    including a ``setsid()``'d escapee that ``killpg`` cannot reach - before
    the uid is reused (SEC-003; see isolation.Workdir).

``--no-new-privs``
    Makes the uid drop irreversible for the whole subtree: no setuid binary can
    raise privileges back.

scrubbed environment
    The child gets ``PATH``/``HOME``/``LANG`` and nothing else. This is the same
    lesson ``EnvIsolatedStdioClientTransport`` learned in core for MCP stdio
    servers - a fresh process inherits the full parent environment by default,
    which is exactly how a credential leaks. This service holds no platform
    credentials in the first place, which is the primary control; scrubbing is
    the belt to that braces.

rlimits
    ``AS`` bounds address space (the memory ceiling), ``CPU`` bounds CPU seconds
    so a spin loop dies even if the wall-clock kill were to fail, ``NPROC``
    bounds thread/process creation, ``FSIZE`` bounds every file the child can
    write - which is how output is capped without a reader thread, since the
    kernel simply kills a process that exceeds it - ``NOFILE`` bounds descriptors
    and ``CORE`` disables core dumps.

output to files, not pipes
    A pipe has no kernel-enforced size limit, so capping pipe output means
    reading it in Python and hoping the producer stops. Redirecting to files
    under ``RLIMIT_FSIZE`` makes the cap a kernel property instead.

wall-clock kill of the whole process group
    ``SIGKILL`` to ``-pgid``, so a child that forked (within its ``NPROC``
    budget) cannot outlive the request. ``start_new_session=True`` makes the
    child a session and process-group leader (``setsid()`` runs in C inside
    ``_posixsubprocess``, so it adds no Python between fork and exec), and
    ``setpriv``/``prlimit`` ``exec`` rather than fork, so the tool process keeps
    that pid and pgid == ``proc.pid``.

    Signalling a process owned by another uid needs ``CAP_KILL``; this service
    is root with every other capability dropped and deliberately runs the child
    as uid 5001, so without ``CAP_KILL`` the kill fails with EPERM and a
    sleeping tool (which ``RLIMIT_CPU`` never reaps - it burns no CPU) would
    outlive its request indefinitely. :func:`verify_kill_capability` therefore
    checks the effective capability set at startup and fails closed when it is
    missing (override: ``TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL=true``), and
    :func:`_kill_group` logs a failed kill at ERROR as degraded enforcement.

optional seccomp
    When ``pyseccomp`` is importable, the child additionally gets a filter that
    fails the socket syscalls outright. This is the one control that does need a
    ``preexec_fn``, so it is opt-in and off by default; the deployment's real
    network control is that this container sits on an ``internal`` Docker
    network shared with nothing but the core API.
"""

from __future__ import annotations

import contextlib
import errno
import logging
import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import IO

log = logging.getLogger(__name__)

try:  # optional, see module docstring
    import pyseccomp

    SECCOMP_AVAILABLE = True
except Exception:  # pragma: no cover - depends on the image
    pyseccomp = None
    SECCOMP_AVAILABLE = False

SETPRIV = shutil.which("setpriv")
PRLIMIT = shutil.which("prlimit")

#: Syscalls denied when seccomp is enabled. Everything else is permitted -
#: this is a targeted network denial, not a full allowlist sandbox.
_DENIED_SYSCALLS = (
    "socket", "socketpair", "connect", "bind", "listen", "accept", "accept4",
    "sendto", "recvfrom", "sendmsg", "recvmsg", "shutdown", "getpeername",
)


@dataclass
class RunResult:
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool
    signalled: int | None
    stdout_truncated: bool


@dataclass
class Limits:
    wall_ms: int
    cpu_seconds: int
    max_output_bytes: int
    #: RLIMIT_AS in MB, or ``None`` to leave address space unbounded.
    #:
    #: ``None`` is the right answer for the JVM and not a shortcut: a HotSpot
    #: JVM *reserves* far more virtual address space than it will ever commit
    #: (compressed class space alone reserves ~1 GB), so an RLIMIT_AS anywhere
    #: near the intended heap size makes the JVM fail to start rather than
    #: making it use less memory. Java runs are bounded by ``-Xmx`` plus
    #: ``-XX:MaxMetaspaceSize`` instead, which bound what is actually committed.
    #: Python has no such reservation behaviour, so it gets a real RLIMIT_AS.
    address_space_mb: int | None = None
    #: NOTE: RLIMIT_NPROC counts processes for the *real uid*, which every
    #: concurrent sandbox run shares. It is therefore set generously and the
    #: service caps concurrency separately - see ``main.py``'s semaphore.
    max_processes: int = 256
    max_open_files: int = 256

    @property
    def file_size_bytes(self) -> int:
        # Generous relative to the output cap: the result file, stdout and
        # stderr each live under this, and javac writes class files under it too.
        return max(self.max_output_bytes * 4, 4 * 1024 * 1024)


class PrivilegeDropUnavailable(RuntimeError):
    """Raised when we are root and cannot drop to the unprivileged uid."""


class KillCapabilityMissing(RuntimeError):
    """Raised at startup when the service could not SIGKILL a dropped-uid child."""


#: Bit index of CAP_KILL in the kernel capability sets (linux/capability.h).
CAP_KILL = 5

_PROC_STATUS = "/proc/self/status"


def parse_cap_eff(status_text: str) -> int | None:
    """Return the ``CapEff`` bitmask from ``/proc/<pid>/status`` text, or None."""
    for line in status_text.splitlines():
        key, sep, value = line.partition(":")
        if sep and key.strip() == "CapEff":
            try:
                return int(value.strip(), 16)
            except ValueError:
                return None
    return None


def has_capability(cap_eff: int | None, cap: int) -> bool:
    return cap_eff is not None and bool(cap_eff & (1 << cap))


def kill_capability_problem(
    *,
    euid: int | None,
    run_uid: int | None,
    status_text: str | None,
) -> str | None:
    """Describe why the wall-clock kill would fail, or None if it will work.

    Pure so it is testable on any host. The kill only needs CAP_KILL when the
    child runs as a *different* uid, i.e. when this process is root and a run
    uid is configured - the situation :func:`build_command`'s setpriv wrapper
    creates. ``euid=None`` means a non-POSIX host, where nothing is dropped.
    """
    if euid is None or euid != 0 or run_uid is None:
        return None
    cap_eff = parse_cap_eff(status_text) if status_text is not None else None
    if has_capability(cap_eff, CAP_KILL):
        return None
    shown = f"{cap_eff:#x}" if cap_eff is not None else "unreadable"
    return (
        f"CAP_KILL is not in this process's effective capability set (CapEff={shown}). "
        f"Tenant code runs as uid {run_uid}, so a timed-out tool cannot be killed: a spin "
        f"loop survives until RLIMIT_CPU and a sleeping tool survives indefinitely. Grant "
        f"it (compose `cap_add: [SETUID, SETGID, KILL]`, k8s "
        f"`securityContext.capabilities.add: [SETUID, SETGID, KILL]`)."
    )


def verify_kill_capability(
    *,
    require_privilege_drop: bool,
    run_uid: int | None,
    allow_missing: bool,
    euid: int | None = None,
    status_text: str | None = None,
) -> None:
    """Startup self-check: fail closed if timeouts could not be enforced.

    Raises :class:`KillCapabilityMissing` when privilege drop is required and
    CAP_KILL is absent, unless ``allow_missing`` (the explicit
    ``TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL`` override) is set, in which case the
    problem is logged at ERROR and the service starts degraded. ``euid`` and
    ``status_text`` default to the real process; tests inject fakes.
    """
    if euid is None:
        geteuid = getattr(os, "geteuid", None)
        euid = geteuid() if geteuid is not None else None
    if status_text is None and euid == 0:
        try:
            status_text = Path(_PROC_STATUS).read_text(encoding="ascii", errors="replace")
        except OSError:
            status_text = None
    problem = kill_capability_problem(euid=euid, run_uid=run_uid, status_text=status_text)
    if problem is None:
        return
    if require_privilege_drop and not allow_missing:
        raise KillCapabilityMissing(
            f"{problem} Refusing to start. Set TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL=true "
            f"only to run knowingly with degraded timeout enforcement.")
    log.error("DEGRADED timeout enforcement: %s", problem)


# --------------------------------------------------------------------------
# Per-execution uids (SEC-003)
# --------------------------------------------------------------------------

class UidPoolExhausted(RuntimeError):
    """Every uid in the configured range is in use (or quarantined)."""


class UidPool:
    """Thread-safe allocator of per-execution uids from an inclusive range.

    Released uids go to the BACK of the queue, so a uid is reused as late as
    possible - a second line behind :func:`kill_uid_processes` in case anything
    of the previous run were somehow still around.
    """

    def __init__(self, first: int, last: int) -> None:
        if first <= 0 or last < first:
            raise ValueError(f"invalid uid range {first}-{last}")
        self._free: deque[int] = deque(range(first, last + 1))
        self._lock = threading.Lock()

    def acquire(self) -> int:
        with self._lock:
            if not self._free:
                raise UidPoolExhausted("no free sandbox uid in the configured range")
            return self._free.popleft()

    def release(self, uid: int) -> None:
        with self._lock:
            self._free.append(uid)

    def available(self) -> int:
        with self._lock:
            return len(self._free)


_PROC_ROOT = Path("/proc")


def uid_pids(uid: int, proc_root: Path = _PROC_ROOT) -> list[int]:
    """Live (non-zombie) pids whose real or effective uid is ``uid``.

    Reads ``/proc/<pid>/status``; returns ``[]`` where there is no /proc (a
    non-Linux dev host), and skips processes that vanish mid-scan.
    """
    try:
        entries = list(proc_root.iterdir())
    except OSError:
        return []
    found: list[int] = []
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            text = (entry / "status").read_text(encoding="ascii", errors="replace")
        except OSError:
            continue
        real = effective = None
        zombie = False
        for line in text.splitlines():
            if line.startswith("Uid:"):
                fields = line.split()
                if len(fields) >= 3:
                    real, effective = fields[1], fields[2]
            elif line.startswith("State:"):
                zombie = line.split()[1:2] == ["Z"]
        if not zombie and str(uid) in (real, effective):
            found.append(int(entry.name))
    return found


def kill_uid_processes(
    uid: int,
    *,
    proc_root: Path = _PROC_ROOT,
    kill: Callable[[int, int], None] | None = None,
    passes: int = 5,
) -> list[int]:
    """SIGKILL every process running as ``uid``; return any that survive.

    This is what makes a per-run uid a real boundary: a tool that ``setsid()``s
    or double-forks out of its process group escapes ``killpg`` but not this.
    Several passes, because a process can fork between the scan and the kill;
    RLIMIT_NPROC bounds how many it can make. Refuses uid 0 and this service's
    own uid outright - never a thing to mass-kill.
    """
    geteuid = getattr(os, "geteuid", None)
    if uid == 0 or (geteuid is not None and uid == geteuid()):
        raise ValueError(f"refusing to mass-kill uid {uid}")
    send = kill if kill is not None else os.kill
    sigkill = getattr(signal, "SIGKILL", 9)
    for _ in range(passes):
        pids = uid_pids(uid, proc_root)
        if not pids:
            return []
        for pid in pids:
            try:
                send(pid, sigkill)
            except ProcessLookupError:
                pass
            except PermissionError:
                log.error("could not SIGKILL pid=%s of sandbox uid %s (EPERM - missing "
                          "CAP_KILL?)", pid, uid)
        time.sleep(0.02)
    return uid_pids(uid, proc_root)


def seccomp_status() -> str:
    if not SECCOMP_AVAILABLE:
        return "unavailable"
    return "available"


def _apply_seccomp() -> None:  # pragma: no cover - requires libseccomp
    """preexec_fn used only when seccomp is explicitly enabled."""
    flt = pyseccomp.SyscallFilter(defaction=pyseccomp.ALLOW)
    for name in _DENIED_SYSCALLS:
        try:
            flt.add_rule(pyseccomp.ERRNO(errno.EPERM), name)
        except Exception:
            # An unknown syscall name on this architecture is not a reason to
            # fail the whole run; the rest of the filter still applies.
            pass
    flt.load()


def build_command(
    argv: list[str],
    limits: Limits,
    *,
    run_uid: int | None,
    run_gid: int | None,
    require_privilege_drop: bool,
) -> list[str]:
    """Wrap ``argv`` in the uid-drop and rlimit helpers.

    Kept separate from :func:`run` so the composed command line is unit-testable
    without actually spawning anything.
    """
    command = list(argv)

    if PRLIMIT:
        prlimit_args = [
            PRLIMIT,
            f"--cpu={limits.cpu_seconds}",
            f"--nproc={limits.max_processes}",
            f"--nofile={limits.max_open_files}",
            f"--fsize={limits.file_size_bytes}",
            "--core=0",
        ]
        if limits.address_space_mb is not None:
            prlimit_args.append(f"--as={limits.address_space_mb * 1024 * 1024}")
        command = [*prlimit_args, "--", *command]

    am_root = hasattr(os, "geteuid") and os.geteuid() == 0
    if am_root and run_uid is not None and run_gid is not None:
        if not SETPRIV:
            if require_privilege_drop:
                raise PrivilegeDropUnavailable(
                    "running as root but setpriv is not installed - refusing to execute "
                    "tenant code with root privileges"
                )
            log.warning("setpriv unavailable; tenant code will run as the service user")
        else:
            command = [
                SETPRIV,
                f"--reuid={run_uid}",
                f"--regid={run_gid}",
                "--clear-groups",
                "--no-new-privs",
                "--",
                *command,
            ]
    elif am_root and require_privilege_drop:
        raise PrivilegeDropUnavailable(
            "running as root with no unprivileged uid configured - refusing to execute "
            "tenant code with root privileges"
        )

    return command


def run(
    argv: list[str],
    *,
    cwd: Path,
    limits: Limits,
    stdin_data: bytes = b"",
    run_uid: int | None = None,
    run_gid: int | None = None,
    require_privilege_drop: bool = True,
    enable_seccomp: bool = False,
    extra_env: dict[str, str] | None = None,
) -> RunResult:
    """Execute ``argv`` under every control described in the module docstring."""
    command = build_command(
        argv,
        limits,
        run_uid=run_uid,
        run_gid=run_gid,
        require_privilege_drop=require_privilege_drop,
    )

    env = {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": str(cwd),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
    }
    if extra_env:
        env.update(extra_env)

    preexec: Callable[[], None] | None = None
    if enable_seccomp and SECCOMP_AVAILABLE:
        preexec = _apply_seccomp

    with tempfile.TemporaryFile() as out_file, tempfile.TemporaryFile() as err_file:
        try:
            proc = subprocess.Popen(  # noqa: S603 - argv is fully constructed here, never a shell string
                command,
                cwd=str(cwd),
                env=env,
                stdin=subprocess.PIPE,
                stdout=out_file,
                stderr=err_file,
                start_new_session=True,
                close_fds=True,
                preexec_fn=preexec,
            )
        except FileNotFoundError as exc:
            return RunResult(127, "", f"interpreter not found: {exc}", False, None, False)

        timed_out = False
        try:
            proc.communicate(input=stdin_data, timeout=limits.wall_ms / 1000.0)
        except subprocess.TimeoutExpired:
            timed_out = True
            # A failed kill is logged at ERROR inside _kill_group, together
            # with the fallbacks it attempted.
            _kill_group(proc)
            try:
                proc.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                log.error("sandboxed process pid=%s survived the kill attempt", proc.pid)

        stdout, stdout_truncated = _read_capped(out_file, limits.max_output_bytes)
        stderr, _ = _read_capped(err_file, limits.max_output_bytes)

    exit_code = proc.returncode if proc.returncode is not None else -1
    signalled = -exit_code if exit_code < 0 else None
    return RunResult(exit_code, stdout, stderr, timed_out, signalled, stdout_truncated)


def _kill_group(proc: subprocess.Popen[bytes]) -> bool:
    """SIGKILL the whole process group, not just the direct child.

    Returns whether the signal was actually delivered. The child was started
    with ``start_new_session=True`` and every wrapper in the chain execs, so its
    pgid is its own pid; signalling ``proc.pid`` as a group directly (rather
    than via ``getpgid``) also avoids a lookup race with an exiting child.

    Both kill paths fail with ``PermissionError`` when the caller lacks
    ``CAP_KILL`` and the target runs as a different uid - a combination this
    service creates deliberately, so it is reported at ERROR as degraded
    enforcement rather than swallowed. :func:`verify_kill_capability` makes
    this unreachable in a correctly configured deployment by refusing to start
    without CAP_KILL. ``ProcessLookupError`` means the process already exited,
    which counts as success.
    """
    try:
        os.killpg(proc.pid, signal.SIGKILL)
        return True
    except ProcessLookupError:
        return True
    except PermissionError:
        log.warning("killpg(%s) was refused (EPERM); falling back to killing the leader",
                    proc.pid)
    try:
        proc.kill()
        log.warning("killed sandboxed leader pid=%s only; any children it forked may "
                    "survive until RLIMIT_CPU", proc.pid)
        return True
    except ProcessLookupError:
        return True
    except PermissionError:
        pass

    # Last-ditch fallbacks. Neither stops the process, but closing our end of
    # stdin makes a tool blocked on reading it see EOF, and the RLIMIT_CPU set
    # through prlimit still bounds a spin loop.
    stdin = getattr(proc, "stdin", None)
    if stdin is not None:
        with contextlib.suppress(OSError):
            stdin.close()
    log.error(
        "DEGRADED timeout enforcement: COULD NOT KILL sandboxed process pid=%s (EPERM on "
        "both killpg and kill). It is still running; RLIMIT_CPU will stop a spin loop, but "
        "a sleeping process will not be reaped. The container is missing CAP_KILL.",
        proc.pid,
    )
    return False


def _read_capped(handle: IO[bytes], cap: int) -> tuple[str, bool]:
    handle.seek(0)
    data = handle.read(cap + 1)
    truncated = len(data) > cap
    if truncated:
        data = data[:cap]
    return data.decode("utf-8", errors="replace"), truncated
