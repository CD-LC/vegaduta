"""Per-execution identity and working directory (SEC-003).

Every execution gets a uid (from the per-run pool when
``TOOL_SANDBOX_RUN_UID_RANGE`` is set, else the shared fallback uid) and a
private directory under its tenant's hashed work root. This module owns how
both are acquired, how file ownership is handed to the run, and how both are
torn down again. :mod:`runner` decides what runs in them.
"""

from __future__ import annotations

import contextlib
import hashlib
import logging
import os
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path

from . import proc
from .settings import Settings

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class RunIdentity:
    """The uid/gid one execution's child processes run as.

    ``per_run`` is True when the uid came from the per-execution pool
    (``TOOL_SANDBOX_RUN_UID_RANGE``) and belongs to this run alone - which is
    what makes it safe to SIGKILL every process of that uid when the run ends.
    Never true for the shared fallback uid, where that would kill other
    tenants' concurrent runs.
    """

    uid: int | None
    gid: int | None
    per_run: bool = False


#: Directory modes (SEC-003). The per-tenant parent is 0711: traversable (the
#: run needs to reach its own directory) but not listable, so one run cannot
#: enumerate other runs' directories. A per-run directory is owned by the run's
#: uid with group 0 and mode 0750 - the run has full access, this service
#: (egid 0, and it holds no CAP_DAC_* capability) can read the result back
#: through the group bit, and every other uid has no access at all.
TENANT_DIR_MODE = 0o711
RUN_DIR_MODE = 0o750
RUN_FILE_MODE = 0o640


def tenant_key(tenant: str) -> str:
    """Hash the tenant into the path.

    The tenant id is a Postgres schema name and already constrained, but
    hashing removes every path-traversal question from this code permanently
    rather than relying on that constraint continuing to hold.
    """
    return hashlib.sha256((tenant or "__no_tenant__").encode("utf-8")).hexdigest()[:32]


def _running_as_root() -> bool:
    return hasattr(os, "geteuid") and os.geteuid() == 0


class RunIsolation:
    """Hands out run identities and owns the files they may write."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.uid_pool: proc.UidPool | None = None
        self._chown_warned = False
        if settings.run_uid_range is not None:
            first, last = settings.run_uid_range
            self.uid_pool = proc.UidPool(first, last)
            log.info("per-execution sandbox uids enabled: %s-%s", first, last)
        elif _running_as_root() and settings.run_uid is not None:
            log.warning(
                "TOOL_SANDBOX_RUN_UID_RANGE is not set: every execution shares uid %s, so "
                "concurrent runs can signal each other and read each other's "
                "/proc/<pid>/environ, and leftover processes cannot be reaped by uid. "
                "Set a range (e.g. 20000-29999) to give each run its own uid.",
                settings.run_uid,
            )

    def workdir(self, tenant: str) -> Workdir:
        return Workdir(self.settings.work_root / tenant_key(tenant), self)

    def acquire_identity(self) -> RunIdentity:
        if self.uid_pool is not None:
            uid = self.uid_pool.acquire()
            return RunIdentity(uid=uid, gid=uid, per_run=True)
        return RunIdentity(uid=self.settings.run_uid, gid=self.settings.run_gid)

    def release_identity(self, ident: RunIdentity) -> None:
        if self.uid_pool is not None and ident.per_run and ident.uid is not None:
            self.uid_pool.release(ident.uid)

    def make_writable(self, path: Path, ident: RunIdentity) -> None:
        """Give this run's uid - and only it - write access to ``path``.

        Owner = the run uid, group = 0 (this service's egid), mode 0750/0640:
        the run can write, the service can read the result back via the group
        bit, and no other uid - another tenant's concurrent run included - can
        even traverse the directory. This needs CAP_CHOWN; without it the chown
        fails and the path falls back to world-writable (0777/0666), which
        keeps runs working but loses that cross-run protection - logged at
        ERROR, once.
        """
        uid = ident.uid
        if uid is not None and _running_as_root():
            try:
                os.chown(path, uid, 0)
                os.chmod(path, RUN_DIR_MODE if path.is_dir() else RUN_FILE_MODE)
                return
            except OSError as exc:
                if not self._chown_warned:
                    self._chown_warned = True
                    log.error(
                        "DEGRADED per-run file isolation: cannot chown sandbox paths to "
                        "uid %s (%s) - the container is missing CAP_CHOWN. Run "
                        "directories fall back to world-writable, so a concurrent run "
                        "that learns a path (e.g. from /proc/<pid>/cmdline) could read "
                        "or overwrite another run's files.", uid, exc)
        try:
            os.chmod(path, 0o777 if path.is_dir() else 0o666)
        except OSError:
            pass

    def reclaim(self, path: Path) -> None:
        """Take a finished run's directories back so they can be deleted.

        Deleting an entry needs write permission on its directory, which after
        :meth:`make_writable` belongs to the run uid; chown every directory
        back to root first (needs CAP_CHOWN, like the forward direction).
        Best-effort - a failure here only leaves garbage under work_root.
        """
        if not _running_as_root():
            return
        for dirpath, _dirnames, _files in os.walk(path):
            with contextlib.suppress(OSError):
                os.chown(dirpath, 0, 0)
                os.chmod(dirpath, 0o700)


class Workdir:
    """One execution's directory AND identity, acquired and torn down together.

    Teardown order matters (SEC-003): first SIGKILL every process of a
    per-run uid - a tool that setsid()/double-forked out of its process
    group survives killpg but not this - THEN delete the directory, so
    nothing can recreate files in it, THEN return the uid to the pool. A
    uid whose processes could not all be killed is quarantined (never
    returned), so a survivor cannot share a uid with a future run.
    """

    def __init__(self, root: Path, owner: RunIsolation) -> None:
        self.root = root
        self.owner = owner
        self.path: Path | None = None
        self.ident: RunIdentity | None = None

    def __enter__(self) -> tuple[Path, RunIdentity]:
        self.ident = self.owner.acquire_identity()
        try:
            self.root.mkdir(parents=True, exist_ok=True)
            with contextlib.suppress(OSError):
                os.chmod(self.root, TENANT_DIR_MODE)
            self.path = Path(tempfile.mkdtemp(prefix="run-", dir=str(self.root)))
            self.owner.make_writable(self.path, self.ident)
        except BaseException:
            self.owner.release_identity(self.ident)
            raise
        return self.path, self.ident

    def __exit__(self, *exc: object) -> None:
        ident = self.ident
        survivors: list[int] = []
        if ident is not None and ident.per_run and ident.uid is not None:
            try:
                survivors = proc.kill_uid_processes(ident.uid)
            except ValueError:
                survivors = []
        if self.path:
            self.owner.reclaim(self.path)
            shutil.rmtree(self.path, ignore_errors=True)
        if ident is None:
            return
        if survivors:
            log.error(
                "sandbox uid %s still has processes %s after SIGKILL; quarantining the "
                "uid (it will not be reused by this process)", ident.uid, survivors)
            return
        self.owner.release_identity(ident)
