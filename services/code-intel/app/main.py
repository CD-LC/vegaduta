"""code-intel microservice - FastAPI surface.

A separate service (like `sidecar`), NOT part of core: tenant-scoped code
indexing, semantic search, and the deterministic AST-derived code graph,
backed by the vendored codebase_rag pipeline.

Error bodies use the platform's {timestamp, status, error} shape so the
web console's existing error handling renders them like any core error.
"""

from __future__ import annotations

import asyncio
import contextlib
import time
from collections.abc import AsyncIterator

from fastapi import BackgroundTasks, Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from . import analysis_routes, reaper
from .agent import PlannerUnavailableError, agent_capabilities
from .auth import AuthError
from .deps import service, tenant
from .executor import ExecDisabledError, WorkspaceError
from .gitops import GitOpsValidationError, RepoTooLargeError
from .registry import RepoEntry
from .service import (
    ApprovalNotAuthorizedError,
    ApprovalRequiredError,
    HeavyJobBusyError,
    PolicyDeniedError,
    RepoBusyError,
    RepoNotFoundError,
)
from .settings import SETTINGS
from .tenancy import TenantContext, TenantValidationError


@contextlib.asynccontextmanager
async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
    """Starts the workspace TTL reaper (see reaper.py) alongside the app
    and cancels it cleanly on shutdown. Runs unconditionally (not gated on
    SETTINGS.allow_exec) because workspaces created while exec was enabled
    must still be reaped even if an operator later disables exec - the
    disk/governance risk doesn't go away with the toggle."""
    reaper_task = asyncio.create_task(reaper.run_forever(SETTINGS))
    try:
        yield
    finally:
        reaper_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await reaper_task


# Interactive docs are disabled by default (SETTINGS.expose_docs) because this
# service is published on a public subdomain - a public Swagger UI / OpenAPI
# schema is unnecessary attack-surface reconnaissance. Passing None for all
# three URLs makes /docs, /redoc, and /openapi.json return 404. Flip
# CODE_INTEL_EXPOSE_DOCS=true (local dev only) to restore them.
app = FastAPI(
    title="code-intel",
    version="0.1.0",
    lifespan=lifespan,
    docs_url="/docs" if SETTINGS.expose_docs else None,
    redoc_url="/redoc" if SETTINGS.expose_docs else None,
    openapi_url="/openapi.json" if SETTINGS.expose_docs else None,
)
# `service`/`tenant` come from app.deps so sibling route modules (analysis_routes,
# any future split) can share the one singleton + auth dependency without a
# circular import back through this FastAPI module.
# Lazy SdlcAgent. app.agent itself is safe to import at module scope (it pulls
# in no LLM SDK - `anthropic` is imported only when a planner is actually
# built), which is what lets /health report the agent's capability without
# constructing anything.
_agent = None

# Browser access for the web module's /code-intel page. Only enabled when
# explicitly configured; server-to-server callers (core, the MCP bridge)
# never need it.
if SETTINGS.cors_origins.strip():
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in SETTINGS.cors_origins.split(",") if o.strip()],
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Tenant-Id", "X-Internal-Token"],
    )


# --------------------------------------------------------------------- #
# error mapping (the auth dependency `tenant` itself lives in app.deps)


def _error(status: int, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "status": status,
            "error": message,
        },
    )


@app.exception_handler(AuthError)
async def _auth_error(_req, exc: AuthError):
    return _error(exc.status, exc.message)


@app.exception_handler(TenantValidationError)
async def _tenant_error(_req, exc):
    return _error(400, str(exc))


@app.exception_handler(GitOpsValidationError)
async def _gitops_validation_error(_req, exc):
    # Caller-input rejections from gitops/service (un-allowlisted auth_env
    # name, local path escaping the tenant's subtree, missing git_url) are
    # 400s raised BEFORE any git/network work - not internal errors.
    return _error(400, str(exc))


@app.exception_handler(RepoTooLargeError)
async def _repo_too_large(_req, exc):
    return _error(413, str(exc))


@app.exception_handler(HeavyJobBusyError)
async def _heavy_busy(_req, exc: HeavyJobBusyError):
    resp = _error(429, str(exc))
    resp.headers["Retry-After"] = str(exc.retry_after)
    return resp


@app.exception_handler(RepoNotFoundError)
async def _notfound(_req, exc):
    return _error(404, f"repo not found: {exc.args[0]}")


@app.exception_handler(RepoBusyError)
async def _busy(_req, exc):
    return _error(409, str(exc))


@app.exception_handler(PolicyDeniedError)
async def _policy_denied(_req, exc):
    return _error(403, f"Blocked by guardrail policy: {exc}")


@app.exception_handler(ApprovalNotAuthorizedError)
async def _approval_not_authorized(_req, exc: ApprovalNotAuthorizedError):
    # Mirrors core's ApprovalNotAuthorizedException / 403 - see
    # ApprovalNotAuthorizedError's docstring in service.py.
    return _error(
        403,
        f"Resolving this approval requires an admin role "
        f"(codeintel.workspace.apply/.run are adminOnly, "
        f"same as on core's Approvals screen): {exc}",
    )


@app.exception_handler(ApprovalRequiredError)
async def _approval_required(_req, exc: ApprovalRequiredError):
    # 428 Precondition Required: caller must obtain approval, then retry with
    # ?approval_id=... once a human approves it.
    return JSONResponse(
        status_code=428,
        content={
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "status": 428,
            "error": f"Human approval required: {exc.reason}",
            "approval_id": exc.approval_id,
            "how_to_proceed": "A reviewer approves via POST /approvals/"
            f"{exc.approval_id}/resolve, then retry this call "
            "with ?approval_id=" + exc.approval_id,
        },
    )


# --------------------------------------------------------------------- #
# models


class RegisterRepoRequest(BaseModel):
    repo_id: str = Field(pattern=r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,127}$")
    source_type: str = Field(pattern="^(local|git)$")
    path: str | None = None
    git_url: str | None = None
    git_ref: str | None = None
    auth_env: str | None = None
    # Direct per-call credential (e.g. a tenant's own BYO PAT), used in
    # preference to auth_env when both are set - see registry.RepoEntry and
    # gitops.ensure_checkout for the precedence rule. Never persisted to
    # disk (registry.save() strips it) and never returned in any response.
    auth_token: str | None = None
    embedder: str | None = None
    embed_model: str | None = None


class SearchRequest(BaseModel):
    query: str
    top_k: int = Field(default=8, ge=1, le=50)
    path_prefix: str | None = None


class IndexRequest(BaseModel):
    full: bool = False
    background: bool = False


# --------------------------------------------------------------------- #
# routes


@app.get("/health")
def health():
    """Liveness + an honest capability report.

    `status`/`auth_mode` are unchanged (core proxies this body straight to the
    console's health pill - see CodeIntelController#health). `capabilities`
    is additive and exists so an operator can see that the optional SDLC
    agent loop is OFF, and WHY, without making a call that fails. The two
    gates are listed separately because they are different problems: an
    unset ANTHROPIC_API_KEY (or a missing `anthropic` package) is a config
    gap; CODE_INTEL_ALLOW_EXEC=false is a host posture, and on Azure
    Container Apps it is not a toggle at all.

    Unauthenticated, so it says only whether a capability is on and which
    setting governs it - never a key, a value, or a hostname.
    """
    return {
        "status": "ok",
        "auth_mode": SETTINGS.auth_mode,
        "capabilities": {"agent_resolve": agent_capabilities(SETTINGS)},
    }


@app.post("/repos", status_code=201)
def register_repo(body: RegisterRepoRequest, ctx: TenantContext = Depends(tenant)):
    entry = service.register(
        ctx,
        RepoEntry(
            repo_id=body.repo_id,
            source_type=body.source_type,
            path=body.path,
            git_url=body.git_url,
            git_ref=body.git_ref,
            auth_env=body.auth_env,
            auth_token=body.auth_token,
            embedder=body.embedder,
            embed_model=body.embed_model,
        ),
    )
    return {"repo_id": entry.repo_id, "source_type": entry.source_type}


@app.get("/repos")
def list_repos(ctx: TenantContext = Depends(tenant)):
    return [
        {
            "repo_id": e.repo_id,
            "source_type": e.source_type,
            "last_index": e.last_index,
            "last_graph": e.last_graph,
            "last_capacity_error": e.last_capacity_error,
        }
        for e in service.list_repos(ctx)
    ]


@app.delete("/repos/{repo_id}")
def delete_repo(repo_id: str, ctx: TenantContext = Depends(tenant)):
    if not service.delete_repo(ctx, repo_id):
        raise RepoNotFoundError(repo_id)
    return {"deleted": repo_id}


@app.delete("/tenant")
def delete_tenant(ctx: TenantContext = Depends(tenant)):
    """Platform-admin "delete tenant completely" control
    (CodeIntelClient#deleteTenantData). Tenant comes from the auth header
    like every other route here, NOT a path param - core always calls this
    with an explicit X-Tenant-Id override (not TenantContext.current(),
    which would be empty for a platform-admin acting on someone else's
    tenant), but the shape of the auth dependency on this side is unchanged.
    Removes every repo/index/graph/workspace this tenant ever had in one
    call, not one DELETE /repos/{repo_id} per repo.
    """
    service.delete_tenant(ctx)
    return {"deleted": ctx.tenant_id}


@app.post("/repos/{repo_id}/index")
def index_repo(
    repo_id: str,
    body: IndexRequest,
    background_tasks: BackgroundTasks,
    ctx: TenantContext = Depends(tenant),
):
    if body.background:
        # The task waits (bounded) for a heavy-job slot; if none frees up the
        # failure is persisted on the repo's last_capacity_error (see /status).
        background_tasks.add_task(_safe_index, ctx, repo_id, body.full)
        return {"status": "accepted", "repo_id": repo_id}
    return service.index(ctx, repo_id, full=body.full)


def _safe_index(ctx: TenantContext, repo_id: str, full: bool) -> None:
    try:
        service.index(ctx, repo_id, full=full, wait=SETTINGS.heavy_queue_wait_seconds)
    except Exception:
        pass  # outcome persisted on the registry entry either way


@app.get("/repos/{repo_id}/status")
def repo_status(repo_id: str, ctx: TenantContext = Depends(tenant)):
    entries = {e.repo_id: e for e in service.list_repos(ctx)}
    if repo_id not in entries:
        raise RepoNotFoundError(repo_id)
    e = entries[repo_id]
    return {
        "repo_id": repo_id,
        "last_index": e.last_index,
        "last_capacity_error": e.last_capacity_error,
        "last_graph": e.last_graph,
    }


@app.post("/repos/{repo_id}/search")
def search(repo_id: str, body: SearchRequest, ctx: TenantContext = Depends(tenant)):
    return service.search(ctx, repo_id, body.query, top_k=body.top_k, path_prefix=body.path_prefix)


@app.post("/repos/{repo_id}/graph/build")
def graph_build(repo_id: str, ctx: TenantContext = Depends(tenant)):
    return service.graph_build(ctx, repo_id)


@app.get("/repos/{repo_id}/graph/callers/{symbol}")
def graph_callers(repo_id: str, symbol: str, ctx: TenantContext = Depends(tenant)):
    return service.graph_callers(ctx, repo_id, symbol)


@app.get("/repos/{repo_id}/graph/callees/{symbol}")
def graph_callees(repo_id: str, symbol: str, ctx: TenantContext = Depends(tenant)):
    return service.graph_callees(ctx, repo_id, symbol)


@app.get("/repos/{repo_id}/graph/impact/{symbol}")
def graph_impact(repo_id: str, symbol: str, depth: int = 3, ctx: TenantContext = Depends(tenant)):
    return service.graph_impact(ctx, repo_id, symbol, depth=depth)


@app.get("/repos/{repo_id}/graph/stats")
def graph_stats(repo_id: str, ctx: TenantContext = Depends(tenant)):
    return service.graph_stats(ctx, repo_id)


# The analysis suite (map/symbols/outline/references/implementations/path/
# dead-code/tests/api-surface/hotspots/import-cycles) lives in
# app/analysis_routes.py - deterministic reads over the built graph, mounted
# here so it shares this app's auth dependency and service singleton.
app.include_router(analysis_routes.router)


# --------------------------------------------------------------------- #
# execution workspaces (rework loop) - 403 unless CODE_INTEL_ALLOW_EXEC


class ApplyFilesRequest(BaseModel):
    files: list[dict] = Field(description="[{path, content} | {path, delete: true}]")


class RunRequest(BaseModel):
    command: str
    timeout: int | None = Field(default=None, ge=1, le=1800)
    attempts: int = Field(
        default=1,
        ge=1,
        le=5,
        description="Reruns of UNCHANGED code on failure (flaky absorption), not rework.",
    )


@app.exception_handler(ExecDisabledError)
async def _exec_disabled(_req, exc):
    return _error(403, str(exc))


@app.exception_handler(PlannerUnavailableError)
async def _planner_unavailable(_req, exc: PlannerUnavailableError):
    # 503, not 500 and not the 403 above: the SDLC agent's LLM planner is a
    # DEPLOYMENT gap (no ANTHROPIC_API_KEY / no anthropic package), which is a
    # different problem, with a different fix, from execution being switched
    # off on this host. Raised before the request does any work, so a 503 here
    # means nothing was created and nothing was run.
    return _error(503, str(exc))


@app.exception_handler(WorkspaceError)
async def _ws_error(_req, exc):
    return _error(400, str(exc))


@app.post("/repos/{repo_id}/workspaces", status_code=201)
def workspace_create(repo_id: str, ctx: TenantContext = Depends(tenant)):
    return service.workspace_create(ctx, repo_id)


@app.post("/repos/{repo_id}/workspaces/{ws_id}/apply")
def workspace_apply(
    repo_id: str,
    ws_id: str,
    body: ApplyFilesRequest,
    approval_id: str | None = None,
    ctx: TenantContext = Depends(tenant),
):
    return service.workspace_apply(ctx, repo_id, ws_id, body.files, approval_id=approval_id)


@app.post("/repos/{repo_id}/workspaces/{ws_id}/run")
def workspace_run(
    repo_id: str,
    ws_id: str,
    body: RunRequest,
    approval_id: str | None = None,
    ctx: TenantContext = Depends(tenant),
):
    return service.workspace_run(
        ctx,
        repo_id,
        ws_id,
        body.command,
        timeout=body.timeout,
        attempts=body.attempts,
        approval_id=approval_id,
    )


@app.get("/repos/{repo_id}/workspaces/{ws_id}/diff")
def workspace_diff(repo_id: str, ws_id: str, ctx: TenantContext = Depends(tenant)):
    return service.workspace_diff(ctx, repo_id, ws_id)


@app.delete("/repos/{repo_id}/workspaces/{ws_id}")
def workspace_delete(repo_id: str, ws_id: str, ctx: TenantContext = Depends(tenant)):
    return service.workspace_delete(ctx, repo_id, ws_id)


# --------------------------------------------------------------------- #
# long-running processes (live-preview dev servers, e.g. `npm run dev`) -
# same 403-unless-CODE_INTEL_ALLOW_EXEC gate as workspaces above. Requires
# CODE_INTEL_RUN_MODE=docker|e2b (executor.start_process rejects "local").


class StartProcessRequest(BaseModel):
    command: str
    run_mode: str | None = Field(default=None, pattern="^(docker|e2b)$")
    image: str | None = None
    port: int | None = Field(default=None, ge=1, le=65535)
    env_overrides: dict[str, str] | None = None


@app.post("/repos/{repo_id}/workspaces/{ws_id}/processes", status_code=201)
def process_start(
    repo_id: str,
    ws_id: str,
    body: StartProcessRequest,
    approval_id: str | None = None,
    ctx: TenantContext = Depends(tenant),
):
    return service.process_start(
        ctx,
        repo_id,
        ws_id,
        body.command,
        run_mode=body.run_mode,
        image=body.image,
        port=body.port,
        env_overrides=body.env_overrides,
        approval_id=approval_id,
    )


@app.delete("/repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}")
def process_stop(repo_id: str, ws_id: str, process_id: str, ctx: TenantContext = Depends(tenant)):
    return service.process_stop(ctx, repo_id, ws_id, process_id)


@app.get("/repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}")
def process_status(repo_id: str, ws_id: str, process_id: str, ctx: TenantContext = Depends(tenant)):
    return service.process_status(ctx, repo_id, ws_id, process_id)


# --------------------------------------------------------------------- #
# guardrails: policy, approvals, audit (per tenant)


class PolicyBody(BaseModel):
    exec_enabled: bool | None = None
    apply_enabled: bool | None = None
    mode: str | None = Field(default=None, pattern="^(read_only|test_gated|autopilot)$")
    max_files_per_apply: int | None = Field(default=None, ge=1, le=500)
    max_bytes_per_file: int | None = Field(default=None, ge=1)
    max_command_timeout: int | None = Field(default=None, ge=1, le=1800)
    writable_path_prefixes: list[str] | None = None
    protected_path_globs: list[str] | None = None


@app.get("/policy")
def get_policy(ctx: TenantContext = Depends(tenant)):
    return service.get_policy(ctx).to_dict()


@app.put("/policy")
def put_policy(body: PolicyBody, ctx: TenantContext = Depends(tenant)):
    # Merge over current so a partial update keeps unspecified fields.
    current = service.get_policy(ctx).to_dict()
    patch = {k: v for k, v in body.model_dump().items() if v is not None}
    current.update(patch)
    return service.set_policy(ctx, current)


@app.get("/approvals")
def list_approvals(ctx: TenantContext = Depends(tenant)):
    return service.list_pending_approvals(ctx)


@app.post("/approvals/{approval_id}/resolve")
def resolve_approval(approval_id: str, approved: bool = True, ctx: TenantContext = Depends(tenant)):
    try:
        return service.resolve_approval(ctx, approval_id, approved)
    except KeyError:
        raise RepoNotFoundError(f"approval {approval_id}")  # noqa: B904


@app.get("/audit")
def audit(limit: int = 100, ctx: TenantContext = Depends(tenant)):
    return service.audit_tail(ctx, limit=min(max(limit, 1), 1000))


# --------------------------------------------------------------------- #
# knowledge-base bridge: read-only, grounded code answers for the
# knowledge experience. Retrieval-only (no exec), so it carries zero
# execution risk — safe to expose in the Knowledge UI / to agents.


class KnowledgeQuery(BaseModel):
    query: str
    repo_id: str | None = None  # omit -> search across all tenant repos
    top_k: int = Field(default=6, ge=1, le=20)


@app.post("/knowledge/query")
def knowledge_query(body: KnowledgeQuery, ctx: TenantContext = Depends(tenant)):
    """Grounded code retrieval for the knowledge base. Returns passages with
    citations (path + line span) and NEVER free-text without a source — the
    RAG grounding guardrail: answers are evidence, not generation."""
    return service.knowledge_query(ctx, body.query, repo_id=body.repo_id, top_k=body.top_k)


# --------------------------------------------------------------------- #
# SDLC agent: autonomous resolve/review loop (context → workspace → fix →
# test → rework → diff). Guardrail-gated and audited via the same paths as
# the Execute tab.
#
# OPTIONAL, and OFF in every environment as configured today - two independent
# gates, each answering with its own status before the request does any work
# (see app/agent.py):
#   403 ExecDisabledError       - CODE_INTEL_ALLOW_EXEC=false on this host.
#   503 PlannerUnavailableError - no ANTHROPIC_API_KEY, and/or no `anthropic`
#                                 package in the image (it is an optional,
#                                 commented-out line in requirements.txt).
# GET /health's capabilities.agent_resolve reports both without a trial call.


class AgentResolveRequest(BaseModel):
    goal: str = Field(description="Issue / bug / change request to resolve")
    test_command: str = Field(
        description="Command that proves success, e.g. 'pytest -q' or 'mvn -q test'"
    )
    max_iterations: int = Field(default=4, ge=1, le=10)


@app.post("/repos/{repo_id}/agent/resolve")
def agent_resolve(repo_id: str, body: AgentResolveRequest, ctx: TenantContext = Depends(tenant)):
    global _agent
    from .agent import SdlcAgent

    if _agent is None:
        _agent = SdlcAgent(service)
    result = _agent.resolve(
        ctx, repo_id, body.goal, body.test_command, max_iterations=body.max_iterations
    )
    return result.to_dict()
