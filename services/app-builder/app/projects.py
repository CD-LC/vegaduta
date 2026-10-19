"""Per-tenant project registry, persisted as JSON in the tenant's own
subtree. Mirrors code-intel/app/registry.py's shape and persistence
pattern (load/upsert/get/delete over a single JSON file, atomic replace).

A Project is app-builder's own record of a scaffolded app: which template
it started from, its status, and the code-intel repo_id it is registered
under (== project_id, 1:1 — see scaffold.py). `preview` and `backend` are
populated once preview.start_preview()/baas.provision_backend() succeed.
"""

from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import asdict, dataclass, field

from .tenancy import TenantContext, tenant_root, validate_project_id

_LOCK = threading.Lock()


class ProjectNotFoundError(KeyError):
    pass


class ProjectExistsError(ValueError):
    pass


class ProjectArchivedError(RuntimeError):
    """The project exists but is archived - an ephemeral app past its
    lifetime, or one a tenant archived early. Mutating routes should refuse
    with 409; reads stay fine."""

    def __init__(self, project_id: str):
        super().__init__(project_id)
        self.project_id = project_id


@dataclass
class Project:
    project_id: str
    tenant_id: str
    template_id: str
    status: str                       # scaffolding|ready|error
    code_intel_repo_id: str
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    error: str | None = None
    preview: dict | None = None       # {process_id, workspace_id, run_mode,
                                       #  preview_url, host, port, started_at}
    backend: dict | None = None       # {schema, database_url, provisioned_at}
    # Bring-your-own-repo state (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md):
    # {repo_full_name, repo_url, base_branch, branch, branch_url, commit_sha,
    #  pr_number, pr_url}. Set when the project lives on a branch in the
    # TENANT's own GitHub repo, which is the default path — None only for
    # legacy/local-dev projects scaffolded with
    # APP_BUILDER_LOCAL_PREVIEW_ENABLED=true and no repo connected.
    #
    # Never carries a token: the PAT is decrypted per request from
    # github_credentials and is never persisted here. Optional with a default
    # so records written before this field existed still load.
    github: dict | None = None

    # Disposable-software lifecycle. "permanent" is the default and identical
    # to pre-lifecycle behaviour; "ephemeral" projects carry expires_at and
    # are meant to self-archive - an agent-built micro-app for one task that
    # lives for days, then steps aside. archived_at marks a soft archive:
    # the record stays (audit/history), mutating routes refuse, and the local
    # preview is stopped best-effort. What archive NEVER does: delete the
    # tenant's GitHub branch/PR or drop the BaaS schema - those are the
    # tenant's resources; expiry changes liveness, not ownership.
    lifecycle: str = "permanent"          # "permanent" | "ephemeral"
    expires_at: float | None = None       # epoch seconds; ephemeral only
    archived_at: float | None = None


# Bounds on how long an ephemeral project may live. 72h default matches the
# "lives for days" product framing; a month is the hard ceiling - past that
# the app is not ephemeral any more, it is a project.
EPHEMERAL = "ephemeral"
PERMANENT = "permanent"
DEFAULT_EPHEMERAL_TTL_HOURS = 72
MAX_EPHEMERAL_TTL_HOURS = 24 * 30
MIN_EPHEMERAL_TTL_HOURS = 1


def is_archived(project: Project) -> bool:
    return project.archived_at is not None


def is_expired(project: Project, now: float | None = None) -> bool:
    return (
        project.lifecycle == EPHEMERAL
        and project.expires_at is not None
        and project.archived_at is None
        and (now if now is not None else time.time()) >= project.expires_at
    )


def require_live(data_dir: str, ctx: TenantContext, project_id: str) -> Project:
    """get_or_404 + a soft-delete check, for mutating routes: an archived
    project can be read and deleted, never generated-against or extended."""
    project = get_or_404(data_dir, ctx, project_id)
    if is_archived(project):
        raise ProjectArchivedError(project_id)
    return project


def archive(data_dir: str, ctx: TenantContext, project_id: str) -> Project:
    """Soft-archives a project. Idempotent - a second archive is a no-op."""
    project = get_or_404(data_dir, ctx, project_id)
    if project.archived_at is None:
        project.archived_at = time.time()
        upsert(data_dir, ctx, project)
    return project


def extend(data_dir: str, ctx: TenantContext, project_id: str,
           extra_hours: float) -> Project:
    """Adds hours to an ephemeral project's lifetime. Refuses on permanent
    projects (nothing to extend) and archived ones (use keep() or delete)."""
    project = require_live(data_dir, ctx, project_id)
    if project.lifecycle != EPHEMERAL:
        raise ValueError("project is permanent - there is no expiry to extend")
    now = time.time()
    base = project.expires_at if project.expires_at and project.expires_at > now else now
    project.expires_at = base + extra_hours * 3600
    return upsert(data_dir, ctx, project)


def keep(data_dir: str, ctx: TenantContext, project_id: str) -> Project:
    """Converts an ephemeral project to permanent and un-archives it - the
    'this turned out to be worth keeping' escape hatch. A no-op on an already
    permanent project (archived_at still cleared if set)."""
    project = get_or_404(data_dir, ctx, project_id)
    project.lifecycle = PERMANENT
    project.expires_at = None
    project.archived_at = None
    return upsert(data_dir, ctx, project)


def expire_due(data_dir: str, ctx: TenantContext,
               now: float | None = None) -> list[Project]:
    """Archives every ephemeral project past its expiry. The sweep is LAZY -
    called from the read paths (list/get), which is where "did this app step
    aside yet" is actually observed. Idempotent by construction."""
    at = now if now is not None else time.time()
    expired = [p for p in load(data_dir, ctx).values() if is_expired(p, at)]
    for p in expired:
        archive(data_dir, ctx, p.project_id)
    return expired


def _registry_path(data_dir: str, ctx: TenantContext) -> str:
    return os.path.join(tenant_root(data_dir, ctx), "projects.json")


def load(data_dir: str, ctx: TenantContext) -> dict[str, Project]:
    path = _registry_path(data_dir, ctx)
    if not os.path.exists(path):
        return {}
    with open(path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    return {pid: Project(**p) for pid, p in raw.items()}


def save(data_dir: str, ctx: TenantContext,
         entries: dict[str, Project]) -> None:
    path = _registry_path(data_dir, ctx)
    tmp = path + ".tmp"
    with _LOCK:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({pid: asdict(p) for pid, p in entries.items()},
                      f, indent=2)
        os.replace(tmp, path)


def upsert(data_dir: str, ctx: TenantContext, project: Project) -> Project:
    validate_project_id(project.project_id)
    project.updated_at = time.time()
    entries = load(data_dir, ctx)
    entries[project.project_id] = project
    save(data_dir, ctx, entries)
    return project


def get(data_dir: str, ctx: TenantContext, project_id: str) -> Project | None:
    return load(data_dir, ctx).get(project_id)


def get_or_404(data_dir: str, ctx: TenantContext, project_id: str) -> Project:
    project = get(data_dir, ctx, project_id)
    if project is None:
        raise ProjectNotFoundError(project_id)
    return project


def delete(data_dir: str, ctx: TenantContext, project_id: str) -> bool:
    entries = load(data_dir, ctx)
    if project_id not in entries:
        return False
    del entries[project_id]
    save(data_dir, ctx, entries)
    return True
