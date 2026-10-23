"""Natural-language instruction -> file edits, applied through code-intel.

Unlike code-intel's own rework loop (which stops at a human-reviewable
diff — see code-intel/app/agent.py's module docstring: "the agent never
merges"), app-builder owns each project end-to-end: there is no separate
human owner of the checkout to hand a diff back to. So a successful
generate() call promotes its result straight into the project's real
checkout — that is the correct/expected behavior for this service, not a
guardrail gap — but every call is still audit-logged (instruction text +
resulting diff, via app/audit.py) so there is a durable record of what the
AI changed and why.

Flow:
  1. retrieve a few relevant files from the project via code-intel's own
     grounded search (reuse its retrieval instead of dumping the whole
     repo into the prompt).
  2. ask core's `POST /api/internal/app-builder/generate-files` for a
     strict set of {path, content} full-file writes (2026-08-06 routing
     fix — this used to call the Anthropic Python SDK directly, hardcoded,
     with no fallback: "Failed: generate() requires: pip install anthropic
     (and ANTHROPIC_API_KEY)". Core now resolves the SAME platform-hosted-
     pool/BYOK routing every other LLM call in this platform already uses
     — see AppBuilderCompletionController/AppBuilderCompletionService in
     core/agents/appbuilder — so prompt-building, model resolution, and the
     provider credential itself all live there now, not here).
  3. apply those files in a scratch code-intel workspace, run the
     template's build/test command if it has one.
  4. on failure, feed the structured failure back to the model for ONE
     corrective retry (not a full N-iteration rework loop — this is v0).
  5. on success, promote the same file set into the project for real —
     since we already hold the exact files we applied, this is simpler and
     more robust than trying to read them back out of a remote workspace we
     have no endpoint for (code-intel's workspace lives in ITS data dir, in
     ITS container; app-builder never reaches into it directly) — then
     re-index via code-intel and delete the workspace.

## Where step 5 promotes to (2026-07-30 bring-your-own-repo pivot)

The Claude call, the retrieval, the apply/run/retry orchestration and the
audit trail are all UNCHANGED by the pivot. Only the promote step moved:

  * bring-your-own-repo project (the default) — the files become ONE commit
    on the project's branch in the TENANT's own GitHub repo, via
    `_promote_to_branch()`. Nothing is written to our filesystem.
  * legacy local project (`APP_BUILDER_LOCAL_PREVIEW_ENABLED=true`, no repo
    connected) — the pre-pivot local write + `git commit`, via
    `_promote_to_local_checkout()`, unchanged.

See `docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md` §3.
"""

from __future__ import annotations

import os
from concurrent.futures import ThreadPoolExecutor

import httpx

from . import audit, github_client, github_credentials, projects
from .codeintel_client import CodeIntelClientError, client as ci_client
from .github_client import GitHubClient, GitHubError
from .scaffold import project_manifest
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, project_root, tenant_root


class GenerateError(RuntimeError):
    pass


# --------------------------------------------------------------------- #
# core's generate-files call — isolated into its own function so tests can
# monkeypatch it directly and exercise modify_project()'s apply/run/retry
# orchestration without needing a running core instance or network access.

_CORE_GENERATE_FILES_PATH = "/api/internal/app-builder/generate-files"


class _FilesResult(list):
    """A plain list of {path, content} file writes carrying the engine that
    produced them. Newer core builds return providerName/model on
    generate-files (so the UI can disclose "powered by ..." for a hosted
    generation); older builds return neither — `powered_by` stays None then
    and the UI simply shows no hosted-engine label, same as before.

    `summary` is the model's own plain-English explanation of the change
    (newer core builds only; None otherwise) — shown as the headline of the
    chat result so the user knows what changed without reading the diff."""

    powered_by: dict | None = None
    summary: str | None = None


def _generate_files(
    ctx: TenantContext,
    instruction: str,
    passages: list[dict],
    manifest: dict,
    prior_failure: dict | None = None,
    context: str | None = None,
    images: list[dict] | None = None,
    settings: Settings = SETTINGS,
    *,
    full_paths: list[str] | None = None,
    project_files: list[str] | None = None,
) -> list[dict]:
    """Calls core's `POST /api/internal/app-builder/generate-files` — the
    same platform-hosted-pool/BYOK routing every other LLM call in this
    platform already uses (AssistService's resolution pattern; see
    AppBuilderCompletionController/AppBuilderCompletionService in
    core/agents/appbuilder) — for a set of {path, content} file writes.
    Prompt-building, model resolution, and the provider credential itself
    all live on core's side now; this function only forwards inputs and
    parses the response. `ctx` is needed (unlike this file's other
    helpers) purely to forward X-Tenant-Id — core's endpoint 401s without
    it. Isolated so tests can monkeypatch this single function instead of
    the network, same as the pre-fix Anthropic call was."""
    retrieved_files = [
        {"path": p.get("path"), "content": p.get("content") or ""} for p in (passages or [])
    ]

    previous_failure = None
    if prior_failure and not prior_failure.get("success"):
        fin = prior_failure.get("final", {})
        previous_failure = (fin.get("stdout", "") + fin.get("stderr", ""))[:4000]

    body = {
        "instruction": instruction,
        "retrievedFiles": retrieved_files,
        "stack": manifest.get("name"),
        "previousFailure": previous_failure,
        # Design inputs (2026-09-26 "next-gen designer" pass): `context` is
        # freeform structured notes the client composed — wireframe spec,
        # extracted palette, SVG markup, user captions; `images` are
        # base64 attachments for vision-capable models (a photo of a
        # hand-drawn wireframe, a Figma export). Both are optional; older
        # core builds ignore unknown fields.
        "context": context,
        # Core's record is camelCase (retrievedFiles/previousFailure) —
        # match it here; our own route takes snake_case and converts.
        "images": [
            {
                "name": i.get("name"),
                "mediaType": i.get("media_type"),
                "dataBase64": i.get("data_base64"),
            }
            for i in images
        ]
        if images
        else None,
        # 2026-09-29: which retrievedFiles are WHOLE files (the rest are
        # code-intel excerpts the model must not rewrite) and the project's
        # file list. Older core builds ignore both.
        "fullFilePaths": list(full_paths or []),
        "projectFiles": list(project_files or []) or None,
    }
    headers = {
        "X-Service-Token": settings.core_service_token,
        "X-Tenant-Id": ctx.tenant_id,
        "Content-Type": "application/json",
    }
    url = settings.core_base_url.rstrip("/") + _CORE_GENERATE_FILES_PATH

    # Same httpx-call-with-error-handling shape as codeintel_client.py's
    # own `_request` (this module's other HTTP-calling neighbor): same two
    # exception types, wrapped into this module's own exception type
    # (GenerateError here, CodeIntelClientError there).
    try:
        resp = httpx.request(
            "POST", url, headers=headers, json=body, timeout=settings.core_timeout_seconds
        )
    except httpx.TimeoutException as e:
        raise GenerateError(f"core generate-files request timed out: {url}") from e
    except httpx.TransportError as e:
        raise GenerateError(f"core unreachable at {url}: {e}") from e

    if resp.status_code >= 400:
        try:
            error_body = resp.json()
        except ValueError:
            error_body = {"error": resp.text[:500]}
        raise GenerateError(error_body.get("error", f"core returned {resp.status_code}"))

    data = resp.json() if resp.content else {}
    files = data.get("files", [])
    result = _FilesResult(
        f
        for f in files
        if isinstance(f, dict) and f.get("path") is not None and f.get("content") is not None
    )
    provider = data.get("providerName")
    model = data.get("model")
    if provider or model:
        result.powered_by = {"provider": provider, "model": model}
    summary = data.get("summary")
    if isinstance(summary, str) and summary.strip():
        result.summary = summary.strip()
    return result


# --------------------------------------------------------------------- #
# What the model sees of the existing project.
#
# code-intel search returns CHUNKS (start/end line excerpts), several per
# file, while the model is asked for FULL-file writes. Sending chunks and
# accepting a full rewrite of the same path silently deletes whatever the
# model never saw. So: the retrieved paths are re-read in full from the
# project (its GitHub branch, or the legacy local checkout); a file too big
# to send whole stays as excerpts, is labelled partial, and any write to it
# is dropped (`drop_partial_writes`).

_MAX_CONTEXT_FILES = 8
_MAX_FULL_FILE_CHARS = 40_000
_MAX_PROJECT_FILES = 300
_SKIP_DIRS = {".git", "node_modules", "dist", "build", ".next", "__pycache__"}


def _project_reader(ctx: TenantContext, project, settings: Settings):
    """(read(path) -> str|None, list() -> [paths]) for the project's current
    state. Best-effort: any failure reads as "unknown", never raises."""
    gh = project.github or {}
    if gh.get("repo_full_name") and gh.get("branch"):
        try:
            conn = github_credentials.require(ctx, settings)
            client = GitHubClient(github_credentials.token_for(conn, settings), settings)
        except Exception:  # noqa: BLE001 - no credential = excerpts only
            return (lambda _p: None), (lambda: [])
        repo, branch = gh["repo_full_name"], gh["branch"]

        def read_gh(path: str) -> str | None:
            try:
                return client.get_file_text(repo, branch, path)
            except GitHubError:
                return None

        def list_gh() -> list[str]:
            try:
                return [e["path"] for e in client.list_tree(repo, branch)]
            except GitHubError:
                return []

        return read_gh, list_gh

    pdir = os.path.realpath(project_root(settings.data_dir, ctx, project.project_id))

    def read_local(path: str) -> str | None:
        abs_path = os.path.realpath(os.path.join(pdir, path.replace("\\", "/").lstrip("/")))
        if not abs_path.startswith(pdir + os.sep) or not os.path.isfile(abs_path):
            return None
        try:
            with open(abs_path, encoding="utf-8", errors="replace") as f:
                return f.read()
        except OSError:
            return None

    def list_local() -> list[str]:
        out: list[str] = []
        for root, dirs, names in os.walk(pdir):
            dirs[:] = [d for d in dirs if d not in _SKIP_DIRS]
            for n in names:
                out.append(os.path.relpath(os.path.join(root, n), pdir).replace(os.sep, "/"))
                if len(out) >= _MAX_PROJECT_FILES:
                    return out
        return out

    return read_local, list_local


def gather_edit_context(
    ctx: TenantContext, project, instruction: str, settings: Settings = SETTINGS
) -> dict:
    """{files:[{path, content, full}], project_files:[...]} for one edit.

    Paths come from code-intel search (relevance), content from the project
    itself (completeness). Used by the hosted generate() and handed to the
    on-device path via POST /projects/{id}/generate/context."""
    try:
        passages = ci_client.search(
            ctx, project.project_id, instruction, top_k=_MAX_CONTEXT_FILES * 2
        )
    except CodeIntelClientError:
        passages = []
    read, list_files = _project_reader(ctx, project, settings)

    chunks: dict[str, list[str]] = {}
    for p in passages or []:
        path = p.get("path")
        if not path:
            continue
        if path not in chunks and len(chunks) >= _MAX_CONTEXT_FILES:
            continue
        chunks.setdefault(path, []).append(p.get("content") or "")

    # Each whole-file read is one independent HTTP (or disk) call — up to
    # _MAX_CONTEXT_FILES of them, fetched in parallel; order of `chunks` is
    # preserved so the model sees the same sequence it always did.
    paths = list(chunks.keys())
    with ThreadPoolExecutor(max_workers=4) as pool:
        wholes = list(pool.map(read, paths))

    files: list[dict] = []
    for path, parts, whole in zip(paths, chunks.values(), wholes):
        if whole is not None and len(whole) <= _MAX_FULL_FILE_CHARS:
            files.append({"path": path, "content": whole, "full": True})
        else:
            files.append({"path": path, "content": "\n...\n".join(parts), "full": False})

    project_files = list_files()[:_MAX_PROJECT_FILES]
    return {"files": files, "project_files": project_files}


def drop_partial_writes(files: list[dict], seen: list[dict]) -> tuple[list[dict], list[str]]:
    """Remove writes to files the model only saw as excerpts. Returns
    (kept, dropped_paths)."""
    partial = {f["path"] for f in seen if not f.get("full")}
    kept = [f for f in files if f.get("path") not in partial]
    dropped = [f["path"] for f in files if f.get("path") in partial]
    return kept, dropped


def _as_passages(seen: list[dict]) -> tuple[list[dict], list[str]]:
    return (
        [{"path": f["path"], "content": f["content"]} for f in seen],
        [f["path"] for f in seen if f.get("full")],
    )


# --------------------------------------------------------------------- #


def modify_project(
    ctx: TenantContext,
    project_id: str,
    instruction: str,
    settings: Settings = SETTINGS,
    *,
    context: str | None = None,
    images: list[dict] | None = None,
    progress=None,
) -> dict:
    """`progress(stage, detail)` is called at each pipeline step so the
    SSE route (/generate/stream) can stream what the turn is doing instead
    of leaving the UI on a dead spinner for the whole call. Stage names:
    "context" (reading the project), "writing" (model generating),
    "files" (detail = the file set - emitted right after workspace apply so
    the client can preview the change while the check still runs),
    "checking" (detail.attempt = build/test attempt number, starting at 1),
    "publishing" (commit + reindex). Optional and best-effort - a raising
    callback never breaks a turn."""

    def emit(stage: str, detail: dict | None = None) -> None:
        if progress is None:
            return
        try:
            progress(stage, detail)
        except Exception:  # noqa: BLE001 - reporting must never fail a turn
            pass

    project = projects.require_live(settings.data_dir, ctx, project_id)
    manifest = project_manifest(ctx, project_id, settings)
    tdir = tenant_root(settings.data_dir, ctx)

    # Not indexed yet / transient failure -> no retrieved files, but the
    # project file list still tells the model what exists.
    emit("context")
    edit_ctx = gather_edit_context(ctx, project, instruction, settings)
    seen = edit_ctx["files"]
    passages, full_paths = _as_passages(seen)
    project_files = edit_ctx["project_files"]

    emit("writing")
    files = _generate_files(
        ctx,
        instruction,
        passages,
        manifest,
        context=context,
        images=images,
        settings=settings,
        full_paths=full_paths,
        project_files=project_files,
    )
    powered_by = getattr(files, "powered_by", None)
    summary = getattr(files, "summary", None)
    files, dropped = drop_partial_writes(list(files), seen)
    if not files:
        raise GenerateError(
            "the model only rewrote files too large to edit safely "
            f"({', '.join(dropped)}) — ask for a smaller, more specific change"
            if dropped
            else "model returned no files to write"
        )

    ws_id = ci_client.create_workspace(ctx, project_id)["workspace_id"]
    try:
        ci_client.apply_files(ctx, project_id, ws_id, files)

        check_cmd = manifest.get("test") or manifest.get("build")
        run_result: dict | None = None
        success = True
        attempts = 0
        # The exact file set is known now - hand it to the client for the
        # in-browser preview BEFORE the (slow) build/test check, so the
        # user sees their change while verification still runs.
        emit("files", {"files": files, "files_changed": [f["path"] for f in files]})
        if check_cmd:
            emit("checking", {"attempt": 1, "total": settings.max_fix_attempts + 1})
            run_result = ci_client.run(ctx, project_id, ws_id, check_cmd)
            success = bool(run_result.get("success"))
            # Bounded self-repair loop: each pass shows the model everything
            # written so far (it is what failed) plus the latest failure
            # output, and keeps the design inputs the first pass had. Stops
            # on success, on the attempt cap, or when the model has nothing
            # more to write.
            while not success and attempts < settings.max_fix_attempts:
                attempts += 1
                emit("writing", {"attempt": attempts + 1})
                retry_seen = _merge_files(seen, [{**f, "full": True} for f in files])
                retry_passages, retry_full = _as_passages(retry_seen)
                retry_files = _generate_files(
                    ctx,
                    instruction,
                    retry_passages,
                    manifest,
                    prior_failure=run_result,
                    context=context,
                    images=images,
                    settings=settings,
                    full_paths=retry_full,
                    project_files=project_files,
                )
                retry_powered = getattr(retry_files, "powered_by", None)
                retry_files, retry_dropped = drop_partial_writes(list(retry_files), retry_seen)
                dropped += retry_dropped
                if not retry_files:
                    break
                ci_client.apply_files(ctx, project_id, ws_id, retry_files)
                powered_by = retry_powered or powered_by
                summary = getattr(retry_files, "summary", None) or summary
                files = _merge_files(files, retry_files)
                emit("files", {"files": files, "files_changed": [f["path"] for f in files]})
                emit("checking", {"attempt": attempts + 1, "total": settings.max_fix_attempts + 1})
                run_result = ci_client.run(ctx, project_id, ws_id, check_cmd)
                success = bool(run_result.get("success"))

        diff = ci_client.diff(ctx, project_id, ws_id).get("diff", "")

        if success:
            emit("publishing")
            _promote(ctx, project, files, instruction, settings)
            project.status = "ready"
            project.error = None
        else:
            project.error = (
                "generate() applied but the build/test command still "
                f"failed after {attempts} fix attempt(s): {check_cmd!r}"
            )
        projects.upsert(settings.data_dir, ctx, project)
    finally:
        try:
            ci_client.delete_workspace(ctx, project_id, ws_id)
        except CodeIntelClientError:
            pass  # best-effort cleanup; the TTL reaper on code-intel's
            # side will eventually collect it regardless.

    audit.record(
        tdir,
        {
            "kind": "generate",
            "project_id": project_id,
            "actor": ctx.subject,
            "instruction": instruction[:2000],
            "files_changed": [f["path"] for f in files],
            "success": success,
            "fix_attempts": attempts,
            "diff_bytes": len(diff),
        },
    )

    return {
        "diff": diff,
        "files_changed": [f["path"] for f in files],
        # The applied file set is echoed back so the client can render
        # an instant in-browser preview (SandboxPreview) without
        # waiting for the Pages deploy — the "next-gen designer" loop.
        "files": files,
        # Which engine generated this change (provider/model; absent on
        # older core builds). The web client renders it as the
        # "powered by" label on each generated result.
        **({"powered_by": powered_by} if powered_by else {}),
        # The model's plain-English explanation of the change (absent on
        # older core builds) - the "what changed" headline in the chat.
        **({"summary": summary} if summary else {}),
        # Writes refused because the model only saw excerpts of that file.
        **({"skipped_files": dropped} if dropped else {}),
        "fix_attempts": attempts,
        "success": success,
        **_github_fields(project),
    }


# --------------------------------------------------------------------- #
# Edge path — files generated client-side (WebLLM / Chrome built-in).
#
# `POST /projects/{id}/generate/apply` is the third leg the core
# completion service's javadoc prescribes: the on-device model produces
# {files:[{path,content}]} in the BROWSER (zero cost — the user's own
# hardware runs the model), posts the file set here, and this function runs
# the exact same apply -> check -> promote pipeline modify_project() does,
# minus the server-side LLM call. The client owns the model, so unlike
# modify_project there is NO corrective retry here: on a failed check this
# returns `check_output` and leaves the retry loop to the caller, which
# feeds the failure back into the on-device model and re-posts.

_MAX_APPLY_FILES = 100
_MAX_APPLY_FILE_BYTES = 512 * 1024


def _validate_apply_files(files: list[dict]) -> list[dict]:
    """Shape-check a client-generated file set before it touches a
    workspace or a git commit. On-device models are weaker than the hosted
    pool, so this is stricter than generate()'s post-filter: a malformed
    batch is a 422 with the offending path named, not a silent drop."""
    if not files:
        raise GenerateError("files must be a non-empty list")
    if len(files) > _MAX_APPLY_FILES:
        raise GenerateError(
            f"too many files ({len(files)} > {_MAX_APPLY_FILES}) — split the "
            "change into smaller instructions"
        )
    out: list[dict] = []
    for f in files:
        if (
            not isinstance(f, dict)
            or not isinstance(f.get("path"), str)
            or not isinstance(f.get("content"), str)
        ):
            raise GenerateError("every file must be {path: str, content: str}")
        try:
            path = github_client.safe_repo_path(f["path"])
        except GitHubError as e:
            raise GenerateError(f"bad file path {f['path']!r}: {e}")
        if len(f["content"].encode("utf-8", errors="replace")) > _MAX_APPLY_FILE_BYTES:
            raise GenerateError(f"file too large (>512KB): {path!r}")
        out.append({"path": path, "content": f["content"]})
    return out


def apply_files_to_project(
    ctx: TenantContext,
    project_id: str,
    instruction: str,
    files: list[dict],
    settings: Settings = SETTINGS,
    *,
    summary: str | None = None,
    progress=None,
) -> dict:
    """Apply a client-generated file set through the same
    workspace/verify/promote/audit pipeline as modify_project().

    `summary` is the client-side model's own explanation of the change —
    echoed back verbatim so the chat result shows it exactly like a hosted
    turn's summary.

    Returns {diff, files_changed, success, check_output?, ...github fields}.
    On a failed check nothing is promoted and `check_output` carries the
    truncated stdout+stderr for the client to feed back to its model."""

    def emit(stage: str, detail: dict | None = None) -> None:
        if progress is None:
            return
        try:
            progress(stage, detail)
        except Exception:  # noqa: BLE001 - reporting must never fail a turn
            pass

    project = projects.require_live(settings.data_dir, ctx, project_id)
    manifest = project_manifest(ctx, project_id, settings)
    tdir = tenant_root(settings.data_dir, ctx)
    files = _validate_apply_files(files)

    ws_id = ci_client.create_workspace(ctx, project_id)["workspace_id"]
    run_result: dict | None = None
    success = True
    try:
        emit("files", {"files": files, "files_changed": [f["path"] for f in files]})
        ci_client.apply_files(ctx, project_id, ws_id, files)

        check_cmd = manifest.get("test") or manifest.get("build")
        if check_cmd:
            emit("checking", {"attempt": 1, "total": 1})
            run_result = ci_client.run(ctx, project_id, ws_id, check_cmd)
            success = bool(run_result.get("success"))

        diff = ci_client.diff(ctx, project_id, ws_id).get("diff", "")

        if success:
            emit("publishing")
            _promote(ctx, project, files, instruction, settings)
            project.status = "ready"
            project.error = None
        else:
            project.error = f"generate/apply ran but the build/test command failed: {check_cmd!r}"
        projects.upsert(settings.data_dir, ctx, project)
    finally:
        try:
            ci_client.delete_workspace(ctx, project_id, ws_id)
        except CodeIntelClientError:
            pass  # best-effort cleanup; code-intel's TTL reaper collects it.

    audit.record(
        tdir,
        {
            "kind": "generate_apply",
            "project_id": project_id,
            "actor": ctx.subject,
            "origin": "client",
            "instruction": (instruction or "")[:2000],
            "files_changed": [f["path"] for f in files],
            "success": success,
            "diff_bytes": len(diff),
        },
    )

    result = {
        "diff": diff,
        "files_changed": [f["path"] for f in files],
        "files": files,
        **({"summary": summary} if summary else {}),
        "success": success,
        **_github_fields(project),
    }
    if not success and run_result:
        fin = run_result.get("final", {})
        result["check_output"] = (fin.get("stdout", "") + fin.get("stderr", ""))[:4000]
    return result


def _github_fields(project) -> dict:
    """The branch/PR/Pages pointers the UI shows after a generate turn.
    Empty for a legacy local project (no github state at all)."""
    gh = project.github or {}
    return {
        "branch": gh.get("branch"),
        "pr_url": gh.get("pr_url"),
        "pr_number": gh.get("pr_number"),
        "pages_url": gh.get("pages_url"),
    }


def _merge_files(base: list[dict], overrides: list[dict]) -> list[dict]:
    """overrides replace same-path entries in base, and are appended
    otherwise — used to fold the corrective retry's files into the set
    promoted to the real checkout."""
    by_path = {f["path"]: f for f in base}
    for f in overrides:
        by_path[f["path"]] = f
    return list(by_path.values())


def _promote(
    ctx: TenantContext, project, files: list[dict], instruction: str, settings: Settings
) -> None:
    """Promote a verified file set into the project for real. Dispatches on
    whether the project lives in the tenant's GitHub repo (the default) or in
    a legacy local checkout."""
    if project.github:
        _promote_to_branch(ctx, project, files, instruction, settings)
    else:
        _promote_to_local_checkout(ctx, project.project_id, files, instruction, settings)


def _promote_to_branch(
    ctx: TenantContext, project, files: list[dict], instruction: str, settings: Settings
) -> None:
    """Commit the file set onto the project's branch in the tenant's repo, as
    ONE commit, over GitHub's Git Data API — nothing is written to this
    host's filesystem. Path-escape safety that used to be a
    `startswith(project_dir)` check is now `github_client.safe_repo_path()`,
    enforced per file inside `commit_files()`.

    The tenant's PAT is decrypted here and dropped when this function
    returns; it is never persisted on the project record or logged.
    """
    gh_state = dict(project.github or {})
    repo = gh_state.get("repo_full_name")
    branch = gh_state.get("branch")
    if not repo or not branch:
        raise GenerateError(
            "project is marked as a GitHub project but has no repo/branch recorded — recreate it"
        )

    conn = github_credentials.require(ctx, settings)
    token = github_credentials.token_for(conn, settings)
    try:
        commit_sha = GitHubClient(token, settings).commit_files(
            repo, branch, files, f"AI: {instruction[:72]}"
        )
    except GitHubError as e:
        # GitHubError messages are already token-scrubbed (see
        # github_client.redact) so this is safe to surface to the caller.
        raise GenerateError(f"could not commit to {repo}@{branch}: {e}") from None

    gh_state["commit_sha"] = commit_sha
    project.github = gh_state

    try:
        # Re-register before re-indexing: code-intel holds `auth_token` in
        # memory only and never persists it (see codeintel_client's
        # register_repo docstring), so a code-intel restart between the
        # scaffold and this call would otherwise leave it unable to fetch a
        # private repo. POST /repos upserts, so this is cheap and idempotent.
        ci_client.register_repo(
            ctx,
            project.project_id,
            source_type="git",
            git_url=f"https://github.com/{repo}.git",
            git_ref=branch,
            auth_token=token,
        )
        ci_client.index_repo(ctx, project.project_id)
    except CodeIntelClientError:
        pass  # the commit is already on the branch, which is the durable
        # result; a later /generate or /index call re-syncs the index.


def _promote_to_local_checkout(
    ctx: TenantContext, project_id: str, files: list[dict], instruction: str, settings: Settings
) -> None:
    """Pre-pivot behaviour, unchanged: write into the local checkout and
    commit. Legacy path — see the module docstring."""
    pdir = os.path.realpath(project_root(settings.data_dir, ctx, project_id))
    for f in files:
        rel = f["path"].replace("\\", "/").lstrip("/")
        abs_path = os.path.realpath(os.path.join(pdir, rel))
        if not abs_path.startswith(pdir + os.sep):
            raise GenerateError(f"generated path escapes project dir: {f['path']!r}")
        os.makedirs(os.path.dirname(abs_path), exist_ok=True)
        with open(abs_path, "w", encoding="utf-8") as out:
            out.write(f.get("content", ""))

    _run_git(["add", "-A"], pdir)
    # Nothing to commit is not an error (e.g. the model rewrote a file
    # with byte-identical content) — check for staged changes first.
    status = _run_git(["status", "--porcelain"], pdir, capture=True)
    if status.strip():
        _run_git(["commit", "-q", "-m", f"AI: {instruction[:72]}"], pdir)

    try:
        ci_client.index_repo(ctx, project_id)
    except CodeIntelClientError:
        pass  # the checkout is still correct on disk; a later /generate or
        # /index call will pick up the fresh state either way.


def _run_git(args: list[str], cwd: str, capture: bool = False) -> str:
    import subprocess

    r = subprocess.run(
        ["git", *args], cwd=cwd, check=True, capture_output=True, text=True, timeout=60
    )
    return r.stdout if capture else ""
