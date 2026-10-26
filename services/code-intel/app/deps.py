"""Shared wiring for route modules: the service singleton and the tenant
dependency. Kept out of ``app.main`` so sibling route modules can import
them without a circular import through the FastAPI app."""

from fastapi import Request

from .auth import authenticate
from .service import CodeIntelService
from .settings import SETTINGS
from .tenancy import TenantContext

service = CodeIntelService(SETTINGS)


def tenant(request: Request) -> TenantContext:
    return authenticate(dict(request.headers), SETTINGS)
