"""Tenant context + per-tenant storage isolation.

Near-identical to code-intel/app/tenancy.py's directory-per-tenant model:
every artifact this service owns (a project's checkout, its registry
record) lives under data/tenants/<tenant_id>/ and no code path ever
constructs a path from anything except the authenticated TenantContext.
Tenant and project ids are validated against strict patterns before
touching the filesystem, so a forged id can never traverse paths.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass

_TENANT_ID_RX = re.compile(r"^[a-z0-9][a-z0-9_\-]{0,62}$")
# project_id doubles as code-intel's repo_id (see scaffold.py) 1:1, so this
# mirrors code-intel/app/tenancy.py's _REPO_ID_RX exactly.
_PROJECT_ID_RX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,127}$")


class TenantValidationError(ValueError):
    pass


@dataclass(frozen=True)
class TenantContext:
    tenant_id: str
    subject: str | None = None
    roles: frozenset[str] = frozenset()
    # True when this request authenticated with the shared X-Internal-Token
    # — i.e. the caller is core (server-to-server), not a browser user. See
    # code-intel/app/tenancy.py's TenantContext.via_internal_token docstring
    # for the equivalent reasoning on that service; app-builder mirrors it
    # for the same reason (a trusted platform caller should not dead-end on
    # a second, independent approval gate this service could grow later).
    via_internal_token: bool = False


def validate_tenant_id(tenant_id: str) -> str:
    if not tenant_id or not _TENANT_ID_RX.match(tenant_id):
        raise TenantValidationError(f"invalid tenant id: {tenant_id!r}")
    return tenant_id


def validate_project_id(project_id: str) -> str:
    if (not project_id or not _PROJECT_ID_RX.match(project_id)
            or ".." in project_id):
        raise TenantValidationError(f"invalid project id: {project_id!r}")
    return project_id


def tenant_root(data_dir: str, ctx: TenantContext) -> str:
    validate_tenant_id(ctx.tenant_id)
    path = os.path.join(data_dir, "tenants", ctx.tenant_id)
    os.makedirs(path, exist_ok=True)
    return path


def project_root(data_dir: str, ctx: TenantContext, project_id: str,
                  create: bool = True) -> str:
    """The project's own checkout directory — NOT a wrapper subtree like
    code-intel's repo_root (that service keeps checkout/state as siblings
    under a repo dir; app-builder has no index/state of its own to keep
    alongside the checkout, code-intel owns that on its side, keyed by
    repo_id == project_id), so this IS the directory the template gets
    copied into and the AI edits."""
    validate_project_id(project_id)
    path = os.path.join(tenant_root(data_dir, ctx), "projects", project_id)
    if create:
        os.makedirs(path, exist_ok=True)
    return path
