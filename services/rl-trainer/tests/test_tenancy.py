"""Tenant ids must not accept a trailing newline (``$`` alone would)."""

from __future__ import annotations

import pytest

from app.tenancy import TenantValidationError, validate_tenant_id


def test_plain_tenant_id_is_accepted() -> None:
    assert validate_tenant_id("acme_1-x") == "acme_1-x"


@pytest.mark.parametrize("tenant", ["acme\n", "ac\nme", "\n"])
def test_newline_in_tenant_id_is_rejected(tenant: str) -> None:
    with pytest.raises(TenantValidationError):
        validate_tenant_id(tenant)
