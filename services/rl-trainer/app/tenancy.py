"""Tenant context + validation, mirroring code-intel/app/tenancy.py.

Tenant ids are validated against a strict pattern before they are ever used
to key an artifact path or an LLM call, so a forged tenant id can never be
used to traverse the filesystem or cross a tenant boundary.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass

_TENANT_ID_RX = re.compile(r"^[a-z0-9][a-z0-9_\-]{0,62}$")


class TenantValidationError(ValueError):
    pass


@dataclass(frozen=True)
class TenantContext:
    tenant_id: str
    subject: str | None = None
    roles: frozenset[str] = frozenset()
    # True when this request authenticated with the shared X-Internal-Token,
    # i.e. the caller is core (server-to-server), NOT a browser user. Same
    # meaning as code-intel's TenantContext.via_internal_token.
    via_internal_token: bool = False


def validate_tenant_id(tenant_id: str) -> str:
    if not tenant_id or not _TENANT_ID_RX.match(tenant_id):
        raise TenantValidationError(f"invalid tenant id: {tenant_id!r}")
    return tenant_id


def tenant_root(data_dir: str, ctx: TenantContext) -> str:
    validate_tenant_id(ctx.tenant_id)
    path = os.path.join(data_dir, "tenants", ctx.tenant_id)
    os.makedirs(path, exist_ok=True)
    return path
