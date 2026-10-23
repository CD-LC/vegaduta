"""app-builder microservice - FastAPI surface.

Sibling of `code-intel` (same directory level, repo root — NOT a subfolder
of it): lets a non-technical consumer describe an app in plain language and
get a running, iteratively-editable app with a live preview. Scaffolds
from a starter-kit template, edits it via natural-language instructions
(through code-intel's grounded search + apply/run, never by vendoring
code-intel's own engine), and starts a live preview process.

As of the 2026-07-30 bring-your-own-repo pivot
(`docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md`) the generated app lands
on a branch in the TENANT's own GitHub repo with a PR opened against their
default branch, and build/preview runs on THEIR GitHub Actions. This service
runs no preview container for it. The `/projects/{id}/preview/*` routes below
are the retained legacy path and refuse unless
`APP_BUILDER_LOCAL_PREVIEW_ENABLED=true`.

Request bodies live in api_models.py and the error mapping (the platform's
{timestamp, status, error} shape) in http_errors.py.
"""

from __future__ import annotations

import json
import queue
import threading
import time

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from . import (
    assets,
    baas,
    dataapi,
    figma,
    github_credentials,
    imagegen,
    mobile_store,
    preview,
    repo_import,
    repo_sync,
    scaffold,
    snapshot,
)
from . import projects as _projects
from .api_models import (
    ApplyFilesRequest,
    ApplySchemaRequest,
    ConnectGitHubRequest,
    CreateProjectRequest,
    DeleteAssetRequest,
    EditContextRequest,
    ExtendProjectRequest,
    FigmaImportRequest,
    GenerateImageRequest,
    GenerateRequest,
    ImportProjectRequest,
    StoreConfigRequest,
    UploadAssetRequest,
)
from .auth import authenticate
from .dataapi_cors import DataCorsMiddleware
from .generate import GenerateError
from .generate import apply_files_to_project as _apply_files_to_project
from .generate import gather_edit_context as _gather_edit_context
from .generate import modify_project as _modify_project
from .http_errors import BadRequest, LocalPreviewOff, install_error_handlers
from .projects import ProjectNotFoundError
from .projects import load as _load_projects
from .settings import SETTINGS
from .tenancy import TenantContext


# Interactive docs default-off, same reasoning as code-intel (public
# subdomain reconnaissance surface). Flip APP_BUILDER_EXPOSE_DOCS=true for
# local dev only.
app = FastAPI(
    title="app-builder",
    version="0.1.0",
    docs_url="/docs" if SETTINGS.expose_docs else None,
    redoc_url="/redoc" if SETTINGS.expose_docs else None,
    openapi_url="/openapi.json" if SETTINGS.expose_docs else None,
)

if SETTINGS.cors_origins.strip():
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in SETTINGS.cors_origins.split(",") if o.strip()],
        allow_credentials=False,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Tenant-Id", "X-Internal-Token"],
    )


# Public data API (see dataapi.py): its own CORS (any origin; the per-project
# allowed-origins rule is enforced on the request) and its routes. Added after
# the console CORSMiddleware so it wraps it for /data/v1/* only.
app.add_middleware(DataCorsMiddleware)
app.include_router(dataapi.router)
app.include_router(dataapi.mgmt)
install_error_handlers(app)


# --------------------------------------------------------------------- #
# auth dependency


def tenant(request: Request) -> TenantContext:
    return authenticate(dict(request.headers), SETTINGS)


# --------------------------------------------------------------------- #
# routes


@app.get("/health")
def health():
    """`github_ready` tells a client whether POST /projects/github/connect can
    work at all on this deployment (i.e. whether APP_BUILDER_ENCRYPTION_KEY is
    set) without having to attempt a connect and read a 503. It reports only
    whether a key EXISTS — never any part of its value."""
    return {
        "status": "ok",
        "auth_mode": SETTINGS.auth_mode,
        "github_ready": bool(SETTINGS.app_builder_encryption_key.strip()),
        "local_preview_enabled": SETTINGS.local_preview_enabled,
    }


@app.get("/templates")
def list_templates(_ctx: TenantContext = Depends(tenant)):
    return scaffold.list_templates(SETTINGS)


# --------------------------------------------------------------------- #
# GitHub connection (bring-your-own-repo).
#
# Declared BEFORE the /projects/{project_id} routes: both are two-segment
# paths, so a literal "github" segment could otherwise be shadowed by
# {project_id} matching on a different verb as this file grows.


@app.get("/projects/github/status")
def github_status(ctx: TenantContext = Depends(tenant)):
    """{connected: false} or {connected, repo_full_name, default_branch,
    connected_by, connected_at, repo_url}. Never the token, and never the
    ciphertext either."""
    return github_credentials.status(ctx, SETTINGS)


@app.post("/projects/github/connect")
def github_connect(body: ConnectGitHubRequest, ctx: TenantContext = Depends(tenant)):
    """Validate the PAT against GitHub's real API, then store it encrypted.

    Rejects immediately (401/403/404) if the token cannot read the repo or
    lacks write access, so a bad token fails here rather than silently on the
    tenant's first build. The response body is `status()`'s shape — the token
    never comes back out.
    """
    return github_credentials.connect(ctx, body.token, body.repo_full_name, SETTINGS)


@app.delete("/projects/github/connect")
def github_disconnect(ctx: TenantContext = Depends(tenant)):
    """Forget the stored credential. Leaves every branch and PR we opened
    untouched in the tenant's repo — that is their code."""
    return {"disconnected": github_credentials.disconnect(ctx, SETTINGS)}


@app.post("/projects", status_code=201)
def create_project(body: CreateProjectRequest, ctx: TenantContext = Depends(tenant)):
    project = scaffold.create_project(ctx, body.project_id, body.template_id, body.prompt, SETTINGS)
    if body.ephemeral:
        project.lifecycle = _projects.EPHEMERAL
        project.expires_at = (
            time.time()
            + (
                body.ttl_hours
                if body.ttl_hours is not None
                else _projects.DEFAULT_EPHEMERAL_TTL_HOURS
            )
            * 3600
        )
        _projects.upsert(SETTINGS.data_dir, ctx, project)
    return _project_dict(project)


def _expire_due_projects(ctx: TenantContext) -> None:
    """Lazy sweep - the app-builder has no scheduler, so expiry is enforced
    where it is observed: the list/get reads. Each expired project is
    archived; a live local preview (legacy deployments only) is stopped
    best-effort. Never touches the tenant's GitHub branch or BaaS schema."""
    try:
        expired = _projects.expire_due(SETTINGS.data_dir, ctx)
    except Exception:
        return
    for p in expired:
        try:
            if p.preview and SETTINGS.local_preview_enabled:
                preview.stop_preview(ctx, p.project_id, SETTINGS)
        except Exception:
            pass  # best-effort - the record is archived either way


@app.post("/projects/{project_id}/archive")
def archive_project(project_id: str, ctx: TenantContext = Depends(tenant)):
    """Soft-archive now: mutating routes 409, the record stays for history.
    Idempotent. Does NOT delete the tenant's branch/PR or BaaS schema."""
    project = _projects.archive(SETTINGS.data_dir, ctx, project_id)
    try:
        if project.preview and SETTINGS.local_preview_enabled:
            preview.stop_preview(ctx, project_id, SETTINGS)
    except Exception:
        pass
    return _project_dict(project)


@app.post("/projects/{project_id}/extend")
def extend_project(
    project_id: str, body: ExtendProjectRequest, ctx: TenantContext = Depends(tenant)
):
    """Adds hours to an ephemeral project's lifetime. 400 on a permanent
    project (nothing to extend), 409 on an archived one."""
    try:
        project = _projects.extend(SETTINGS.data_dir, ctx, project_id, body.ttl_hours)
    except ValueError as e:
        raise BadRequest(str(e))
    return _project_dict(project)


@app.post("/projects/{project_id}/keep")
def keep_project(project_id: str, ctx: TenantContext = Depends(tenant)):
    """ "This micro-app turned out to be worth keeping" - converts an
    ephemeral project to permanent and clears any archive marker."""
    return _project_dict(_projects.keep(SETTINGS.data_dir, ctx, project_id))


@app.post("/projects/import", status_code=201)
def import_project(body: ImportProjectRequest, ctx: TenantContext = Depends(tenant)):
    project = repo_import.import_project(
        ctx, body.repo_full_name, body.base_branch, body.subdir, body.project_id, SETTINGS
    )
    return _project_dict(project)


@app.get("/projects/{project_id}/sync/status")
def sync_status(project_id: str, ctx: TenantContext = Depends(tenant)):
    return repo_sync.status(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/sync/pull")
def sync_pull(project_id: str, ctx: TenantContext = Depends(tenant)):
    return repo_sync.pull(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/sync/detect-external")
def sync_detect_external(project_id: str, ctx: TenantContext = Depends(tenant)):
    return repo_sync.detect_external(ctx, project_id, SETTINGS)


@app.get("/projects")
def list_projects(ctx: TenantContext = Depends(tenant)):
    _expire_due_projects(ctx)
    return [_project_dict(p) for p in _load_projects(SETTINGS.data_dir, ctx).values()]


@app.get("/projects/{project_id}")
def get_project(project_id: str, ctx: TenantContext = Depends(tenant)):
    from . import projects

    _expire_due_projects(ctx)
    return _project_dict(projects.get_or_404(SETTINGS.data_dir, ctx, project_id))


@app.delete("/projects/{project_id}")
def delete_project(project_id: str, ctx: TenantContext = Depends(tenant)):
    if not scaffold.delete_project(ctx, project_id, SETTINGS):
        raise ProjectNotFoundError(project_id)
    dataapi.revoke_project(ctx, project_id, SETTINGS)  # its public API keys die with it
    return {"deleted": project_id}


@app.post("/projects/{project_id}/generate")
def generate(project_id: str, body: GenerateRequest, ctx: TenantContext = Depends(tenant)):
    return _modify_project(
        ctx,
        project_id,
        body.instruction,
        context=body.context,
        images=[i.model_dump() for i in body.images] if body.images else None,
        settings=SETTINGS,
    )


@app.post("/projects/{project_id}/generate/stream")
def generate_stream(project_id: str, body: GenerateRequest, ctx: TenantContext = Depends(tenant)):
    """The same pipeline as POST /generate, streamed as Server-Sent Events
    so the UI can show live progress ("reading your app…", "writing
    changes…", "checking it builds…", "publishing…") instead of one
    opaque 60-120 s spinner — and so the browser can render the new files'
    preview the moment they are written, while the build/test check is
    still running.

    Each SSE `data:` line is a JSON object:
      {"stage": "context"|"writing"|"files"|"checking"|"publishing", ...}
      - "files" carries {"files", "files_changed"} (the exact applied set —
        render it in the sandbox preview immediately)
      - "writing"/"checking" may carry {"attempt", "total"} during the
        bounded self-repair loop
      {"stage": "done", "result": {...the same body /generate returns}}
      {"stage": "error", "status": <http-style code>, "error": "..."}

    The pipeline itself is sync+blocking, so it runs on a worker thread and
    reports through a queue. Auth/404 errors that would fail the sync route
    up front (missing project) still fail BEFORE the stream opens."""
    # Pre-validate what the sync route fails on before any body is sent.
    _projects.get_or_404(SETTINGS.data_dir, ctx, project_id)

    events_q: queue.Queue = queue.Queue()

    def progress(stage: str, detail: dict | None = None) -> None:
        events_q.put({"stage": stage, **(detail or {})})

    def work() -> None:
        try:
            result = _modify_project(
                ctx,
                project_id,
                body.instruction,
                context=body.context,
                images=[i.model_dump() for i in body.images] if body.images else None,
                settings=SETTINGS,
                progress=progress,
            )
            events_q.put({"stage": "done", "result": result})
        except HTTPException as e:
            events_q.put({"stage": "error", "status": e.status_code, "error": str(e.detail)})
        except GenerateError as e:
            events_q.put({"stage": "error", "status": 500, "error": str(e)})
        except Exception as e:  # noqa: BLE001 - the stream must end cleanly
            events_q.put({"stage": "error", "status": 500, "error": str(e)})
        finally:
            events_q.put(None)

    threading.Thread(target=work, daemon=True).start()

    def events():
        while True:
            item = events_q.get()
            if item is None:
                return
            yield "data: " + json.dumps(item, default=str) + "\n\n"

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/projects/{project_id}/generate/context")
def generate_context(
    project_id: str, body: EditContextRequest, ctx: TenantContext = Depends(tenant)
):
    """The existing files an edit should see - the same retrieval the hosted
    /generate route uses - for the on-device path, which otherwise asked a
    local model for full-file writes of files it had never seen. `full` is
    false for files too large to send whole; the client must not write
    those."""
    from . import projects

    project = projects.get_or_404(SETTINGS.data_dir, ctx, project_id)
    return _gather_edit_context(ctx, project, body.instruction, SETTINGS)


@app.post("/projects/{project_id}/generate/apply")
def generate_apply(project_id: str, body: ApplyFilesRequest, ctx: TenantContext = Depends(tenant)):
    return _apply_files_to_project(
        ctx,
        project_id,
        body.instruction,
        [f.model_dump() for f in body.files],
        SETTINGS,
        summary=body.summary,
    )


# --------------------------------------------------------------------- #
# LEGACY Docker-preview routes.
#
# Retained deliberately (the contract's "do not delete preview.py" rule) but
# gated OFF by default: build/preview for a bring-your-own-repo project runs
# on the tenant's own GitHub Actions, and a merged PR deploys wherever they
# already deploy. Running preview containers here was the dominant infra cost
# this pivot exists to remove, so nothing in the create/generate flow calls
# these any more — a client has to ask for them explicitly, and only a
# deployment with APP_BUILDER_LOCAL_PREVIEW_ENABLED=true will answer.


def _require_local_preview() -> None:
    if not SETTINGS.local_preview_enabled:
        raise LocalPreviewOff(
            "local Docker previews are disabled on this deployment. This "
            "project's code lives on a branch in your own GitHub repository — "
            "see the project's `github.pr_url` — and builds run on your own "
            "GitHub Actions."
        )


@app.post("/projects/{project_id}/preview/start")
def preview_start(project_id: str, ctx: TenantContext = Depends(tenant)):
    _require_local_preview()
    return preview.start_preview(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/preview/stop")
def preview_stop(project_id: str, ctx: TenantContext = Depends(tenant)):
    _require_local_preview()
    return preview.stop_preview(ctx, project_id, SETTINGS)


@app.get("/projects/{project_id}/preview")
def preview_get(project_id: str, ctx: TenantContext = Depends(tenant)):
    """Reports {active: false, enabled: false} rather than 409 when local
    previews are off: a UI polling for preview state should render "no
    preview" calmly, not an error toast."""
    if not SETTINGS.local_preview_enabled:
        return {"active": False, "enabled": False}
    return preview.preview_status(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/design/figma")
def design_figma(project_id: str, body: FigmaImportRequest, ctx: TenantContext = Depends(tenant)):
    """{context, image?} for generate()'s context/images params."""
    return figma.import_for_project(ctx, project_id, body.url, body.token, SETTINGS)


@app.post("/projects/{project_id}/backend/provision")
def backend_provision(project_id: str, ctx: TenantContext = Depends(tenant)):
    return baas.provision_backend(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/backend/schema")
def backend_apply_schema(
    project_id: str, body: ApplySchemaRequest, ctx: TenantContext = Depends(tenant)
):
    """Run migration SQL as the project's own Postgres role (see baas.py)."""
    return baas.apply_schema(ctx, project_id, body.sql, SETTINGS)


@app.get("/projects/{project_id}/backend/tables")
def backend_tables(project_id: str, ctx: TenantContext = Depends(tenant)):
    return baas.list_tables(ctx, project_id, SETTINGS)


# --------------------------------------------------------------------- #
# Assets — user-uploaded material (logo, photo, Figma export, font) that
# lands under assets/ on the project's branch. Base64 JSON, not multipart:
# keeps the request shape identical to every other route here and avoids a
# python-multipart dependency.


@app.get("/projects/{project_id}/files")
def project_files_route(project_id: str, ctx: TenantContext = Depends(tenant)):
    """The project's current text file set — powers the instant in-browser
    preview (SandboxPreview) and the Design panel's token read."""
    return {"files": snapshot.project_files(ctx, project_id, SETTINGS)}


@app.get("/projects/{project_id}/assets")
def list_project_assets(project_id: str, ctx: TenantContext = Depends(tenant)):
    return {"assets": assets.list_assets(ctx, project_id, SETTINGS)}


@app.post("/projects/{project_id}/assets", status_code=201)
def upload_project_asset(
    project_id: str, body: UploadAssetRequest, ctx: TenantContext = Depends(tenant)
):
    import base64

    try:
        data = base64.b64decode(body.content_base64, validate=True)
    except (ValueError, TypeError):
        raise GenerateError("content_base64 is not valid base64")
    return assets.upload_asset(ctx, project_id, body.name, data, SETTINGS)


@app.post("/projects/{project_id}/assets/generate", status_code=201)
def generate_project_asset(
    project_id: str, body: GenerateImageRequest, ctx: TenantContext = Depends(tenant)
):
    return imagegen.generate_asset(ctx, project_id, body.prompt, body.kind, body.size, SETTINGS)


@app.delete("/projects/{project_id}/assets")
def delete_project_asset(
    project_id: str, body: DeleteAssetRequest, ctx: TenantContext = Depends(tenant)
):
    return assets.delete_asset(ctx, project_id, body.path, SETTINGS)


def _project_dict(project) -> dict:
    from dataclasses import asdict

    return asdict(project)


@app.get("/projects/{project_id}/mobile/store-config")
def mobile_store_status(project_id: str, ctx: TenantContext = Depends(tenant)):
    return mobile_store.store_status(ctx, project_id, SETTINGS)


@app.post("/projects/{project_id}/mobile/store-config")
def mobile_store_config(
    project_id: str, body: StoreConfigRequest, ctx: TenantContext = Depends(tenant)
):
    return mobile_store.apply_store_config(ctx, project_id, body.model_dump(), SETTINGS)
