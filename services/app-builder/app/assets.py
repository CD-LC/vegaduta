"""Project assets — logos, images, fonts, design exports the user uploads.

Part of the "next-gen designer" surface (2026-09-26): a non-technical user
builds faster when they can drop their own material into the app — a logo,
a photo, a Figma SVG export, a font — and then refer to it by name in the
chat ("use my logo in the header"). Assets are therefore first-class
project files: uploaded once, committed to `assets/<name>` on the project's
branch (ONE commit, same Git Data API path as generated code), listed back
for the UI's asset strip, and visible to the generation model because
code-intel indexes them like any other repo file.

Unlike generate()/apply, an asset upload does NOT run the build check —
a binary blob cannot break the build, and skipping the workspace round-trip
keeps upload latency to one GitHub commit.

Safety: filenames are sanitized to a strict allowlist of characters and
extensions (an uploaded file is user content, but the path it lands at is
ours to choose — never let "logo.png" become "../../.github/workflows/x").
Sizes are capped (assets travel through this service and through a browser
client; multi-hundred-MB files are a git-abuse vector anyway).
"""

from __future__ import annotations

import os
import re

from . import audit, github_credentials, projects
from .codeintel_client import CodeIntelClientError, client as ci_client
from .github_client import GitHubClient, GitHubError, safe_repo_path
from .generate import GenerateError, _run_git
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, project_root, tenant_root

_MAX_ASSET_BYTES = 8 * 1024 * 1024  # 8 MB decoded
_MAX_ASSETS_PER_PROJECT = 50

# What a generated app can actually use. SVG is allowed deliberately: it is
# both a displayable asset AND machine-readable design input (a Figma
# export's markup can be fed to the text model — see the web client's
# designInputs.ts). Filenames are rewritten to a safe charset.
_FILENAME_RX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,120}$")
_ALLOWED_EXT = {
    "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif",
    "woff", "woff2", "ttf", "otf",
    "mp3", "mp4", "webm", "pdf", "json",
}

ASSETS_DIR = "assets"


def _safe_asset_name(name: str) -> str:
    base = os.path.basename((name or "").replace("\\", "/")).strip()
    base = re.sub(r"[^A-Za-z0-9._\-]", "_", base)
    if not base or not _FILENAME_RX.match(base):
        raise GenerateError(f"not a usable asset name: {name!r}")
    ext = base.rsplit(".", 1)[-1].lower() if "." in base else ""
    if ext not in _ALLOWED_EXT:
        raise GenerateError(
            f"unsupported asset type .{ext or '(none)'} — allowed: "
            + ", ".join(sorted(_ALLOWED_EXT)))
    return base


def upload_asset(ctx: TenantContext, project_id: str, filename: str,
                 data: bytes, settings: Settings = SETTINGS) -> dict:
    """Commit one asset to `assets/<name>` on the project's branch (BYO) or
    into the legacy local checkout. Returns {path, bytes, commit_sha?}."""
    project = projects.require_live(settings.data_dir, ctx, project_id)
    name = _safe_asset_name(filename)
    if len(data) > _MAX_ASSET_BYTES:
        raise GenerateError(
            f"asset too large ({len(data)} bytes > {_MAX_ASSET_BYTES})")
    if len(list_assets(ctx, project_id, settings)) >= _MAX_ASSETS_PER_PROJECT:
        raise GenerateError(
            f"too many assets (>{_MAX_ASSETS_PER_PROJECT}) — delete unused "
            "ones first")

    rel = f"{ASSETS_DIR}/{name}"
    if project.github:
        gh_state = dict(project.github)
        repo, branch = gh_state.get("repo_full_name"), gh_state.get("branch")
        conn = github_credentials.require(ctx, settings)
        token = github_credentials.token_for(conn, settings)
        try:
            commit_sha = GitHubClient(token, settings).commit_files(
                repo, branch,
                [{"path": rel, "content": data}],
                f"Asset: {name}")
        except GitHubError as e:
            raise GenerateError(
                f"could not commit asset to {repo}@{branch}: {e}") from None
        gh_state["commit_sha"] = commit_sha
        project.github = gh_state
        projects.upsert(settings.data_dir, ctx, project)
        result = {"path": rel, "bytes": len(data), "commit_sha": commit_sha}
    else:
        # Legacy local checkout (APP_BUILDER_LOCAL_PREVIEW_ENABLED).
        pdir = os.path.realpath(project_root(settings.data_dir, ctx,
                                             project_id))
        abs_path = os.path.realpath(os.path.join(pdir, rel))
        if not abs_path.startswith(pdir + os.sep):
            raise GenerateError(f"asset path escapes project dir: {rel!r}")
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)
        with open(abs_path, "wb") as f:
            f.write(data)
        _run_git(["add", "-A"], pdir)
        _run_git(["commit", "-q", "-m", f"Asset: {name}"], pdir)
        result = {"path": rel, "bytes": len(data)}

    try:
        ci_client.index_repo(ctx, project_id)
    except CodeIntelClientError:
        pass  # the commit is durable; a later index call re-syncs.

    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "asset_uploaded", "project_id": project_id,
        "actor": ctx.subject, "path": rel, "bytes": len(data)})
    return result


def list_assets(ctx: TenantContext, project_id: str,
                settings: Settings = SETTINGS) -> list[dict]:
    """[{path, size}] under assets/ — BYO projects list from the branch's
    git tree (Git Data API, nothing cloned); legacy local from disk."""
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    if project.github:
        gh_state = project.github or {}
        repo, branch = gh_state.get("repo_full_name"), gh_state.get("branch")
        try:
            conn = github_credentials.require(ctx, settings)
            token = github_credentials.token_for(conn, settings)
            tree = GitHubClient(token, settings).list_tree(repo, branch)
        except (github_credentials.GitHubNotConnectedError,
                github_credentials.CredentialDecryptError,
                github_credentials.EncryptionNotConfiguredError,
                GitHubError):
            return []
        return [{"path": e["path"], "size": e.get("size")}
                for e in tree if e["path"].startswith(ASSETS_DIR + "/")]

    pdir = project_root(settings.data_dir, ctx, project_id, create=False)
    adir = os.path.join(pdir, ASSETS_DIR)
    if not os.path.isdir(adir):
        return []
    out = []
    for dirpath, _dn, filenames in os.walk(adir):
        for fn in sorted(filenames):
            ap = os.path.join(dirpath, fn)
            rel = os.path.relpath(ap, pdir).replace(os.sep, "/")
            out.append({"path": rel, "size": os.path.getsize(ap)})
    return out


def delete_asset(ctx: TenantContext, project_id: str, path: str,
                 settings: Settings = SETTINGS) -> dict:
    """Remove one asset. BYO: committing a file with `delete=True`... Git
    Data API deletes by omitting the path from a NEW tree, so this
    re-lists the branch tree minus the asset and commits that."""
    project = projects.require_live(settings.data_dir, ctx, project_id)
    rel = safe_repo_path(path)
    if not rel.startswith(ASSETS_DIR + "/"):
        raise GenerateError(f"can only delete files under {ASSETS_DIR}/")

    if project.github:
        gh_state = dict(project.github)
        repo, branch = gh_state.get("repo_full_name"), gh_state.get("branch")
        conn = github_credentials.require(ctx, settings)
        token = github_credentials.token_for(conn, settings)
        gh = GitHubClient(token, settings)
        commit_sha = gh.delete_files(repo, branch, [rel], f"Remove asset: {rel}")
        gh_state["commit_sha"] = commit_sha
        project.github = gh_state
        projects.upsert(settings.data_dir, ctx, project)
    else:
        pdir = os.path.realpath(project_root(settings.data_dir, ctx,
                                             project_id))
        abs_path = os.path.realpath(os.path.join(pdir, rel))
        if not abs_path.startswith(pdir + os.sep):
            raise GenerateError(f"asset path escapes project dir: {rel!r}")
        if os.path.exists(abs_path):
            os.remove(abs_path)
            _run_git(["add", "-A"], pdir)
            _run_git(["commit", "-q", "-m", f"Remove asset: {rel}"], pdir)

    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "asset_deleted", "project_id": project_id,
        "actor": ctx.subject, "path": rel})
    return {"deleted": rel}
