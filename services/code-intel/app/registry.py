"""Per-tenant repo registry, persisted as JSON in the tenant's own subtree.

A repo is either:
- local: an already-present path (mounted volume, CI checkout), or
- git:   a URL this service clones/fetches itself. Credentials are NEVER
         stored on disk. Two mechanisms supply a token at call time:
         - `auth_env` names an environment variable holding a shared,
           platform-operator-managed token (the original mechanism - one
           token per container, set at process start).
         - `auth_token` carries a direct, per-tenant/per-repo credential
           (e.g. a tenant's own BYO GitHub PAT) supplied by the caller on
           the request that needs it (register/clone). It takes precedence
           over `auth_env` when both are present (see gitops.py). Like
           `auth_env`'s resolved value, it is NEVER written to `.git/config`,
           argv, or logs (see gitops.py's ephemeral credential helper) -
           and unlike `auth_env`, the raw value itself is also never
           persisted to the registry.json file: `save()` strips it before
           writing, so an entry loaded back from disk always has
           `auth_token=None` and must be re-supplied by the caller on any
           subsequent call that needs it.
"""

from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import asdict, dataclass, field, fields

from .tenancy import TenantContext, tenant_root, validate_repo_id

_LOCK = threading.Lock()


def allowed_auth_env() -> frozenset[str]:
    """Env-var NAMES that ``RepoEntry.auth_env`` is permitted to reference.

    ``auth_env`` is a caller-supplied env-var name resolved against THIS
    process's environment and injected as the git basic-auth password toward
    the caller's ``git_url`` (see gitops.ensure_checkout). Without an
    allowlist any secret in the container env (``CODE_INTEL_INTERNAL_TOKEN``,
    Keycloak config, ...) could be exfiltrated to an attacker-controlled
    remote. Only operator-managed git-credential env vars belong here: the
    built-in ``CODE_INTEL_GIT_TOKEN`` plus any names an operator explicitly
    adds via ``CODE_INTEL_ALLOWED_AUTH_ENV`` (comma-separated) - e.g. the
    documented per-tenant ``GITHUB_TOKEN_<TENANT>`` provisioning pattern.
    """
    names = {"CODE_INTEL_GIT_TOKEN"}
    extra = os.environ.get("CODE_INTEL_ALLOWED_AUTH_ENV", "")
    names.update(n.strip() for n in extra.split(",") if n.strip())
    return frozenset(names)


@dataclass
class RepoEntry:
    repo_id: str
    source_type: str                  # "local" | "git"
    path: str | None = None          # local source
    git_url: str | None = None       # git source
    git_ref: str | None = None       # branch/tag (default: remote HEAD)
    auth_env: str | None = None      # env var NAME holding a token
    auth_token: str | None = None    # direct per-call token (never persisted -
                                      # see module docstring; wins over auth_env)
    embedder: str | None = None      # per-repo override
    embed_model: str | None = None
    created_at: float = field(default_factory=time.time)
    last_index: dict | None = None   # {status, stats, started_at, finished_at, error}
    last_graph: dict | None = None
    # Capacity/admission failures (heavy-job queue timeout) are recorded here,
    # NOT in last_index, so they never wipe the last successful index status.
    last_capacity_error: dict | None = None


def _registry_path(data_dir: str, ctx: TenantContext) -> str:
    return os.path.join(tenant_root(data_dir, ctx), "registry.json")


def load(data_dir: str, ctx: TenantContext) -> dict[str, RepoEntry]:
    path = _registry_path(data_dir, ctx)
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    # Tolerate keys this build does not know (files written by a NEWER image
    # during a rolling deploy/rollback) instead of crashing on RepoEntry(**e).
    known = {f.name for f in fields(RepoEntry)}
    return {rid: RepoEntry(**{k: v for k, v in e.items() if k in known})
            for rid, e in raw.items()}


def _serializable(entry: RepoEntry) -> dict:
    # auth_token is a direct, caller-supplied credential (e.g. a tenant's
    # BYO PAT) - it must never be written to disk, so it is stripped here
    # regardless of what's set in-memory. auth_env (an env-var NAME, not a
    # secret itself) persists, but ONLY when it names an allowlisted
    # credential env var - an un-allowlisted name is never persisted, so a
    # caller cannot smuggle an arbitrary env-var name past the resolution
    # gate in gitops by having it round-trip through registry.json.
    d = asdict(entry)
    d["auth_token"] = None
    # Fields added after the first release are omitted while empty so an OLD
    # image (strict RepoEntry(**e)) can still read files written by this one.
    # Only a repo with a recorded capacity error carries the new key.
    if d.get("last_capacity_error") is None:
        d.pop("last_capacity_error", None)
    if d.get("auth_env") and d["auth_env"] not in allowed_auth_env():
        d["auth_env"] = None
    return d


def save(data_dir: str, ctx: TenantContext,
         entries: dict[str, RepoEntry]) -> None:
    path = _registry_path(data_dir, ctx)
    tmp = path + ".tmp"
    with _LOCK:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({rid: _serializable(e) for rid, e in entries.items()},
                      f, indent=2)
        os.replace(tmp, path)


def upsert(data_dir: str, ctx: TenantContext, entry: RepoEntry) -> RepoEntry:
    validate_repo_id(entry.repo_id)
    entries = load(data_dir, ctx)
    entries[entry.repo_id] = entry
    save(data_dir, ctx, entries)
    return entry


def get(data_dir: str, ctx: TenantContext, repo_id: str) -> RepoEntry | None:
    return load(data_dir, ctx).get(repo_id)


def delete(data_dir: str, ctx: TenantContext, repo_id: str) -> bool:
    entries = load(data_dir, ctx)
    if repo_id not in entries:
        return False
    del entries[repo_id]
    save(data_dir, ctx, entries)
    return True
