"""Workspace TTL reaper.

`executor.py` creates scratch git-worktree/copy workspaces for the
apply/run/diff rework loop, but nothing ever forced their cleanup: a
workspace created and never explicitly deleted (client crash, an
abandoned approval, a bug in a calling agent) sat on disk forever. That
is both a disk-exhaustion risk and a data-retention/governance gap - an
old scratch copy of a tenant's proprietary code with no expiry.

This module is a periodic sweep, started as a plain asyncio background
task from main.py's lifespan (no new dependency - see requirements.txt,
which has neither APScheduler nor anything similar). Each sweep:

  1. walks the on-disk workspace layout (tenancy.py's directory-per-tenant
     convention: data/tenants/<tenant_id>/repos/<repo_id>/workspaces/<ws_id>),
  2. deletes any workspace older than CODE_INTEL_WORKSPACE_TTL_HOURS via
     executor.delete_workspace() - the SAME path callers use, so it never
     bypasses whatever safety checks that function has - skipping any
     workspace executor.is_busy() reports as mid-execution,
  3. records a `workspace_reaped` audit event per deletion (same JSONL
     audit trail as every other workspace mutation - see audit.py), and
  4. stops after MAX_DELETIONS_PER_SWEEP deletions so a bug in the reaper
     itself can't cascade into wiping every workspace in one pass; the
     remainder is picked up on the next sweep.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Iterator

from . import audit, executor
from .settings import Settings

logger = logging.getLogger(__name__)

# How often the background loop sweeps. Not exposed as a Settings field
# (only the TTL is operator-relevant per the task); change here if the
# default cadence needs tuning.
DEFAULT_SWEEP_INTERVAL_SECONDS = 900  # 15 minutes

# Safety ceiling: never delete more than this many workspaces in a single
# sweep, regardless of how many are stale.
MAX_DELETIONS_PER_SWEEP = 50


def _iter_workspace_dirs(data_dir: str) -> Iterator[tuple[str, str, str, str]]:
    """Yield (tenant_id, repo_id, workspaces_dir, ws_id) for every workspace
    tracked on disk, mirroring tenancy.py's layout:
    <data_dir>/tenants/<tenant_id>/repos/<repo_id>/workspaces/<ws_id>/
    """
    tenants_dir = os.path.join(data_dir, "tenants")
    if not os.path.isdir(tenants_dir):
        return
    for tenant_id in sorted(os.listdir(tenants_dir)):
        repos_dir = os.path.join(tenants_dir, tenant_id, "repos")
        if not os.path.isdir(repos_dir):
            continue
        for repo_id in sorted(os.listdir(repos_dir)):
            workspaces_dir = os.path.join(repos_dir, repo_id, "workspaces")
            if not os.path.isdir(workspaces_dir):
                continue
            for ws_id in sorted(os.listdir(workspaces_dir)):
                if not ws_id.startswith("ws-"):
                    continue  # not a workspace dir (e.g. stray file)
                yield tenant_id, repo_id, workspaces_dir, ws_id


def sweep(settings: Settings, now: float | None = None,
          max_deletions: int = MAX_DELETIONS_PER_SWEEP) -> dict:
    """Run one reaper pass. Returns a summary dict (also useful for tests).

    A workspace is reaped when `now - meta["created_at"] >= ttl_seconds`
    AND `executor.is_busy(ws_id)` is False. `iterations` (rework edits
    already applied) does not exempt a workspace - an abandoned workspace
    with edits applied is exactly the disk/governance risk this closes.
    """
    now = time.time() if now is None else now
    ttl_seconds = settings.workspace_ttl_hours * 3600
    summary = {"scanned": 0, "reaped": 0, "busy_skipped": 0,
              "error_skipped": 0, "cap_hit": False}

    for tenant_id, repo_id, workspaces_dir, ws_id in _iter_workspace_dirs(
            settings.data_dir):
        summary["scanned"] += 1
        if summary["reaped"] >= max_deletions:
            summary["cap_hit"] = True
            break

        try:
            meta = executor.load_workspace(workspaces_dir, ws_id)
        except Exception:
            # Corrupt/partial metadata (e.g. crash mid-write) - leave it for
            # operator investigation rather than guessing at deletion.
            summary["error_skipped"] += 1
            continue

        age_seconds = now - meta.get("created_at", now)
        if age_seconds < ttl_seconds:
            continue
        if executor.is_busy(ws_id):
            summary["busy_skipped"] += 1
            continue

        try:
            executor.delete_workspace(workspaces_dir, ws_id)
        except Exception:
            logger.exception(
                "reaper: failed to delete workspace ws_id=%s tenant=%s "
                "repo=%s", ws_id, tenant_id, repo_id)
            summary["error_skipped"] += 1
            continue

        summary["reaped"] += 1
        age_hours = round(age_seconds / 3600, 2)
        logger.info(
            "reaper: deleted stale workspace ws_id=%s tenant=%s repo=%s "
            "age_hours=%s ttl_hours=%s",
            ws_id, tenant_id, repo_id, age_hours, settings.workspace_ttl_hours)
        try:
            tenant_dir = os.path.join(settings.data_dir, "tenants", tenant_id)
            audit.record(tenant_dir, {
                "kind": "workspace_reaped",
                "actor": "system:workspace_reaper",
                "repo_id": repo_id,
                "workspace_id": ws_id,
                "age_hours": age_hours,
                "ttl_hours": settings.workspace_ttl_hours,
            })
        except Exception:
            logger.exception(
                "reaper: deleted ws_id=%s but failed to record the audit "
                "event", ws_id)

    if summary["cap_hit"]:
        logger.warning(
            "reaper: hit the per-sweep deletion cap (%s); more stale "
            "workspaces remain and will be handled on the next sweep",
            max_deletions)
    return summary


async def run_forever(settings: Settings,
                      interval_seconds: int = DEFAULT_SWEEP_INTERVAL_SECONDS
                      ) -> None:
    """Background loop: sweep, sleep, repeat - cancelled on app shutdown.

    `sweep` does blocking filesystem/subprocess work (git worktree
    remove), so it runs off the event loop thread via asyncio.to_thread.

    Known limitation: the "last swept at" state is in-memory only (this
    coroutine's loop position); it is not persisted, so a process restart
    simply starts a fresh interval rather than resuming a prior schedule.
    That's acceptable here since each sweep is a full, idempotent rescan
    of every workspace on disk, not an incremental cursor.
    """
    logger.info(
        "workspace reaper started: ttl_hours=%s interval_seconds=%s",
        settings.workspace_ttl_hours, interval_seconds)
    while True:
        try:
            await asyncio.to_thread(sweep, settings)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("reaper: sweep failed")
        await asyncio.sleep(interval_seconds)
