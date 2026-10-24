"""tool-sandbox HTTP API.

The only caller is core's ``ToolSandboxClient``. Two properties of this service
matter more than anything in this file:

1. **It holds no platform credentials.** No database URL, no
   ``APP_ENCRYPTION_KEY``, no Keycloak secret, no provider API key. Even a
   complete compromise of this container yields nothing that unlocks tenant
   data, because none of it is here to steal.
2. **It has no route to the platform's data tier.** It is attached to a single
   ``internal`` Docker network shared with the core API and nothing else - no
   Postgres, no Redis, no Keycloak, no internet.

Everything else - the bytecode verifier, the AST checks, the uid drop, the
rlimits - is defence in depth layered on top of those two.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import cast

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from . import proc, runner
from .logsafe import clean
from .models import (
    ErrorType,
    ExecuteRequest,
    ExecuteResponse,
    HealthResponse,
    ValidateRequest,
    ValidateResponse,
    ValidationError,
)
from .settings import settings

logging.basicConfig(
    level=os.environ.get("TOOL_SANDBOX_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
log = logging.getLogger("tool-sandbox")

VERSION = "1"


@asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Startup self-checks that must fail closed.

    Refuses to start (uvicorn exits, the orchestrator surfaces a crash loop with
    this message) when timeouts could not be enforced because CAP_KILL is
    missing - see proc.verify_kill_capability.
    """
    proc.verify_kill_capability(
        require_privilege_drop=settings.require_privilege_drop,
        run_uid=settings.run_uid,
        allow_missing=settings.allow_missing_cap_kill,
    )
    yield


app = FastAPI(
    title="VegaDuta tool-sandbox",
    version=VERSION,
    description=(
        "Compiles, verifies and executes tenant-authored Java, Python, JavaScript and "
        "TypeScript tools in isolation."
    ),
    docs_url=None,  # no interactive docs on a service that executes code
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)

_runner = runner.Runner(settings)
# Bounds concurrent executions. Beyond load control this matters because
# RLIMIT_NPROC is per-uid and every run shares one uid - see proc.Limits.
_slots = asyncio.Semaphore(settings.max_concurrent_executions)


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    """Shared-secret auth, compared in constant time.

    There is deliberately no unauthenticated mode: an empty configured token
    rejects everything rather than allowing everything, so a deployment that
    forgets to set ``TOOL_SANDBOX_INTERNAL_TOKEN`` fails closed.
    """
    expected = settings.internal_token
    if not expected:
        log.error("TOOL_SANDBOX_INTERNAL_TOKEN is not configured; rejecting request")
        raise HTTPException(status_code=503, detail="sandbox is not configured")
    if not hmac.compare_digest(x_internal_token or "", expected):
        raise HTTPException(status_code=403, detail="invalid internal token")


def require_tenant(x_tenant_id: str = Header(default="")) -> str:
    tenant = (x_tenant_id or "").strip()
    if not tenant:
        raise HTTPException(status_code=400, detail="X-Tenant-Id is required")
    return tenant


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Unauthenticated liveness only - it reveals no tenant data."""
    if settings.run_uid is None:
        privilege_drop = "not-configured"
    elif proc.SETPRIV:
        privilege_drop = "setpriv"
    else:
        privilege_drop = "unavailable"
    return HealthResponse(
        status="ok",
        version=VERSION,
        languages=list(runner.LANGUAGES),
        seccomp="enabled"
        if (settings.enable_seccomp and proc.SECCOMP_AVAILABLE)
        else proc.seccomp_status(),
        privilegeDrop=privilege_drop,
    )


@app.post(
    "/v1/validate", response_model=ValidateResponse, dependencies=[Depends(require_internal_token)]
)
async def validate(
    request: ValidateRequest,
    tenant: str = Depends(require_tenant),
) -> ValidateResponse:
    """Compile-and-verify without running.

    A rejection is a 200 with ``valid: false`` - it is a verdict about the
    tenant's program, not a failure of this call, and core stores it against
    the tool so the author can see and fix it.
    """
    async with _slots:
        outcome = await run_in_threadpool(
            _runner.validate, request.language, tenant, request.source
        )
    return ValidateResponse(
        valid=outcome.valid,
        sourceSha256=outcome.source_sha256,
        # Same coercion pydantic would apply to the raw dicts, made explicit
        # so the call type-checks.
        errors=[ValidationError.model_validate(e) for e in outcome.errors],
        warnings=outcome.warnings,
    )


@app.post(
    "/v1/execute", response_model=ExecuteResponse, dependencies=[Depends(require_internal_token)]
)
async def execute(
    request: ExecuteRequest,
    tenant: str = Depends(require_tenant),
) -> ExecuteResponse:
    timeout_ms = settings.clamp_timeout_ms(request.timeoutMs)
    memory_mb = settings.clamp_memory_mb(request.memoryMb)

    async with _slots:
        outcome = await run_in_threadpool(
            _runner.execute,
            request.language,
            tenant,
            request.source,
            request.input,
            timeout_ms,
            memory_mb,
            request.secrets,
        )

    if not outcome.ok:
        # Tenant-visible failures are logged without the source or the input:
        # a tool's input is tenant data and has no business in this service's
        # logs, which is the same rule core's GlobalExceptionHandler follows.
        log.info(
            "tool execution failed tool=%s language=%s type=%s duration=%dms",
            clean(request.toolName),
            clean(request.language),
            outcome.error_type,
            outcome.duration_ms,
        )

    return ExecuteResponse(
        ok=outcome.ok,
        result=outcome.result,
        error=outcome.error,
        # runner only ever produces members of the ErrorType set; pydantic
        # still validates the value at runtime.
        errorType=cast("ErrorType | None", outcome.error_type),
        stdout=outcome.stdout,
        stderr=outcome.stderr if not outcome.ok else "",
        durationMs=outcome.duration_ms,
        truncated=outcome.truncated,
    )


@app.exception_handler(Exception)
async def unhandled(request: Request, exc: Exception) -> JSONResponse:
    """Never leak an internal stack trace to the caller."""
    log.exception("unhandled error on %s", clean(request.url.path))
    return JSONResponse(status_code=500, content={"detail": "sandbox internal error"})
