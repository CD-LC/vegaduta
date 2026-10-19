"""HTTP error mapping for the app-builder API (see main.py).

Error bodies use the platform's {timestamp, status, error} shape, same as
code-intel's own main.py, so any UI treats both services' errors
identically. `install_error_handlers` registers one handler per domain
exception on the FastAPI app.
"""

from __future__ import annotations

import time

from fastapi import FastAPI
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import figma, repo_import, repo_sync
from .auth import AuthError
from .codeintel_client import CodeIntelClientError
from .generate import GenerateError
from .github_client import GitHubError, InvalidRepoNameError
from .github_credentials import (
    CredentialDecryptError,
    EncryptionNotConfiguredError,
    GitHubNotConnectedError,
)
from .projects import ProjectArchivedError, ProjectExistsError, ProjectNotFoundError
from .scaffold import LocalPreviewDisabledError, TemplateNotFoundError
from .tenancy import TenantValidationError


class BadRequest(ValueError):
    """A route-level 400 (e.g. extending a permanent project)."""


class LocalPreviewOff(RuntimeError):
    """A legacy Docker-preview route on a deployment that disabled them (409)."""


def error_response(status: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "status": status,
            "error": message,
        },
    )


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AuthError)
    async def _auth_error(_req, exc: AuthError):
        return error_response(exc.status, exc.message)

    @app.exception_handler(TenantValidationError)
    async def _tenant_error(_req, exc):
        return error_response(400, str(exc))

    @app.exception_handler(ProjectNotFoundError)
    async def _project_not_found(_req, exc):
        return error_response(404, f"project not found: {exc.args[0]}")

    @app.exception_handler(ProjectExistsError)
    async def _project_exists(_req, exc):
        return error_response(409, str(exc))

    @app.exception_handler(ProjectArchivedError)
    async def _project_archived(_req, exc):
        return error_response(409, f"project is archived: {exc}")

    @app.exception_handler(TemplateNotFoundError)
    async def _template_not_found(_req, exc):
        return error_response(404, f"template not found: {exc.args[0]}")

    @app.exception_handler(GenerateError)
    async def _generate_error(_req, exc):
        return error_response(422, str(exc))

    @app.exception_handler(GitHubNotConnectedError)
    async def _github_not_connected(_req, exc):
        # 409, not 401/403: the caller authenticated fine, the tenant just has no
        # repo connected yet. Their next step is to connect one, not to retry.
        return error_response(409, str(exc))

    @app.exception_handler(LocalPreviewDisabledError)
    async def _local_preview_disabled(_req, exc):
        return error_response(409, str(exc))

    @app.exception_handler(EncryptionNotConfiguredError)
    async def _encryption_not_configured(_req, exc):
        # 503: a deployment gap (APP_BUILDER_ENCRYPTION_KEY unset or invalid), not
        # the caller's fault. We refuse to store a PAT unencrypted as a fallback.
        return error_response(503, str(exc))

    @app.exception_handler(CredentialDecryptError)
    async def _credential_decrypt_error(_req, exc):
        return error_response(409, str(exc))

    @app.exception_handler(InvalidRepoNameError)
    async def _invalid_repo_name(_req, exc):
        return error_response(400, str(exc))

    @app.exception_handler(GitHubError)
    async def _github_error(_req, exc: GitHubError):
        # GitHubError messages are token-scrubbed at construction
        # (github_client.redact), so propagating them cannot leak a PAT.
        status = exc.status if 400 <= exc.status < 600 else 502
        return error_response(status, f"GitHub: {exc.message}")

    @app.exception_handler(figma.FigmaError)
    async def _figma_error(_req, exc: figma.FigmaError):
        return error_response(exc.status, f"Figma: {exc.message}")

    @app.exception_handler(RequestValidationError)
    async def _validation_error(req, exc: RequestValidationError):
        # FastAPI's default 422 echoes the request body ("input"), which on the
        # Figma route would return the access token. Redact for that route only.
        if req.url.path.endswith("/design/figma"):
            return error_response(422, "Figma: request needs a string url and token")
        return await request_validation_exception_handler(req, exc)

    @app.exception_handler(repo_import.ImportTooLargeError)
    async def _import_too_large(_req, exc):
        return error_response(413, str(exc))

    @app.exception_handler(repo_import.StackNotDetectedError)
    async def _stack_not_detected(_req, exc):
        return error_response(422, str(exc))

    @app.exception_handler(repo_sync.SyncUnavailableError)
    async def _sync_unavailable(_req, exc):
        return error_response(409, str(exc))

    @app.exception_handler(repo_sync.SyncConflictError)
    async def _sync_conflict(_req, exc: repo_sync.SyncConflictError):
        return JSONResponse(
            status_code=409,
            content={
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "status": 409,
                "error": str(exc),
                "conflicted_paths": exc.paths,
            },
        )

    @app.exception_handler(CodeIntelClientError)
    async def _codeintel_error(_req, exc: CodeIntelClientError):
        # Propagate code-intel's own status where it's a sane HTTP code (its
        # guardrail 403/428 in particular are meaningful to the caller); fall
        # back to 502 for transport failures (status 0) or anything odd.
        status = exc.status if 400 <= exc.status < 600 else 502
        return error_response(status, f"code-intel: {exc.message}")

    @app.exception_handler(BadRequest)
    async def _bad_request(_req, exc):
        return error_response(400, str(exc))

    @app.exception_handler(LocalPreviewOff)
    async def _local_preview_off(_req, exc):
        return error_response(409, str(exc))
