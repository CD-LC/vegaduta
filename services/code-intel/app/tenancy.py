"""Tenant context + per-tenant storage isolation.

The platform isolates tenants with schema-per-tenant Postgres; this service
mirrors that with directory-per-tenant storage: every artifact (clone,
registry, SQLite index/graph) lives under data/tenants/<tenant_id>/ and no
code path ever constructs a path from anything except the authenticated
TenantContext. Tenant ids are validated against a strict pattern before
touching the filesystem, so a forged tenant id can never traverse paths.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass

_TENANT_ID_RX = re.compile(r"^[a-z0-9][a-z0-9_\-]{0,62}$")
_REPO_ID_RX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,127}$")


class TenantValidationError(ValueError):
    pass


@dataclass(frozen=True)
class TenantContext:
    tenant_id: str
    subject: str | None = None
    roles: frozenset[str] = frozenset()
    # True when this request authenticated with the shared X-Internal-Token,
    # i.e. the caller is core (server-to-server), NOT a browser user. core has
    # already authenticated the end user AND run its own admin-gated
    # HumanApprovalGateService before any write reaches us, so an internal
    # caller is treated as trusted/pre-approved for code-intel's own ASK gate
    # (never for DENY - hard-denies, caps and protected paths still fire). See
    # CodeIntelService._is_admin / workspace_apply and the
    # code-intel-approval-reconciliation findings for why this closes the
    # "platform approved, then dead-ends on code-intel's own 428" gap.
    via_internal_token: bool = False


def validate_tenant_id(tenant_id: str) -> str:
    if not tenant_id or not _TENANT_ID_RX.fullmatch(tenant_id):
        raise TenantValidationError(f"invalid tenant id: {tenant_id!r}")
    return tenant_id


def validate_repo_id(repo_id: str) -> str:
    if not repo_id or not _REPO_ID_RX.fullmatch(repo_id) or ".." in repo_id:
        raise TenantValidationError(f"invalid repo id: {repo_id!r}")
    return repo_id


def tenant_root(data_dir: str, ctx: TenantContext) -> str:
    validate_tenant_id(ctx.tenant_id)
    path = os.path.join(data_dir, "tenants", ctx.tenant_id)
    os.makedirs(path, exist_ok=True)
    return path


def repo_root(data_dir: str, ctx: TenantContext, repo_id: str) -> str:
    validate_repo_id(repo_id)
    path = os.path.join(tenant_root(data_dir, ctx), "repos", repo_id)
    os.makedirs(path, exist_ok=True)
    return path
