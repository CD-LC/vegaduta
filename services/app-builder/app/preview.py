"""Ties process-start on code-intel to a URL a browser can load.

start_preview(): read the project's template manifest for run/port/image,
create a code-intel workspace FROM THE REAL CHECKOUT (not a scratch
diff-loop workspace — code-intel's `POST /repos/{id}/workspaces` builds
from the registered repo's current HEAD, which is exactly what preview
wants), start the process, and register a (dev-only, see ingress.py) URL
for it.

stop_preview(): the reverse — deregister the route, stop the process,
and drop the scratch workspace.

Depends on code-intel's new process endpoints, landing in a parallel
workstream — see codeintel_client.py's module docstring for the assumed
contract and where to fix it if the landed shape differs.
"""

from __future__ import annotations

import time

from . import audit, ingress, projects
from .codeintel_client import CodeIntelClientError, client as ci_client
from .scaffold import project_manifest
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root


class PreviewError(RuntimeError):
    pass


def _install_and_run_command(manifest: dict) -> str:
    install = (manifest.get("install") or "").strip()
    run = manifest["run"]
    return f"{install} && {run}" if install else run


def start_preview(ctx: TenantContext, project_id: str,
                  settings: Settings = SETTINGS) -> dict:
    project = projects.require_live(settings.data_dir, ctx, project_id)
    manifest = project_manifest(ctx, project_id, settings)

    ws = ci_client.create_workspace(ctx, project_id)
    ws_id = ws["workspace_id"]

    command = _install_and_run_command(manifest)
    try:
        proc = ci_client.create_process(
            ctx, project_id, ws_id, command, run_mode="docker",
            image=manifest.get("image"), port=manifest.get("port"))
    except CodeIntelClientError:
        # Don't leak the scratch workspace if process start fails.
        try:
            ci_client.delete_workspace(ctx, project_id, ws_id)
        except CodeIntelClientError:
            pass
        raise

    if proc.get("preview_url"):
        preview_url = proc["preview_url"]
    else:
        preview_url = ingress.register_preview_route(
            project_id, proc["host"], proc["port"])

    project.preview = {
        "process_id": proc["process_id"], "workspace_id": ws_id,
        "run_mode": proc.get("run_mode", "docker"),
        "preview_url": preview_url,
        "host": proc.get("host"), "port": proc.get("port"),
        "started_at": time.time(),
    }
    projects.upsert(settings.data_dir, ctx, project)

    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "preview_started", "project_id": project_id,
        "actor": ctx.subject, "preview_url": preview_url})

    return {"preview_url": preview_url}


def stop_preview(ctx: TenantContext, project_id: str,
                 settings: Settings = SETTINGS) -> dict:
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    if not project.preview:
        return {"stopped": False, "reason": "no active preview"}

    ingress.deregister_preview_route(project_id)
    try:
        ci_client.delete_process(
            ctx, project_id, project.preview["workspace_id"],
            project.preview["process_id"])
    except CodeIntelClientError:
        pass
    try:
        ci_client.delete_workspace(
            ctx, project_id, project.preview["workspace_id"])
    except CodeIntelClientError:
        pass

    project.preview = None
    projects.upsert(settings.data_dir, ctx, project)

    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "preview_stopped", "project_id": project_id,
        "actor": ctx.subject})

    return {"stopped": True}


def preview_status(ctx: TenantContext, project_id: str,
                   settings: Settings = SETTINGS) -> dict:
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    if not project.preview:
        return {"active": False}

    live: dict = {}
    try:
        live = ci_client.get_process(
            ctx, project_id, project.preview["workspace_id"],
            project.preview["process_id"])
    except CodeIntelClientError:
        pass  # stale/unreachable process info; report what we last knew

    return {"active": True, **project.preview,
           "running": live.get("running"), "exit_code": live.get("exit_code")}
