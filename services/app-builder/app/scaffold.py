"""Template catalog + project scaffolding.

Turns "template_id + optional NL prompt" into a project living on a branch in
the **tenant's own GitHub repository**, with a PR open against their default
branch, registered with code-intel so the AI can search it.

## What changed (2026-07-30 bring-your-own-repo pivot)

Superseded: the old flow copied the template into
`data/tenants/<t>/projects/<id>/` on THIS host, `git init`-ed it, registered
it with code-intel as a `local` repo, and previewed it in a Docker container
we ran. Those preview containers were becoming the dominant infra cost on a
single shared 8vCPU/11.68GB host running ~20 other services, so the whole
model moved out to the tenant:

    read template into memory (never onto our disk)
      -> create branch <prefix>/<project_id> in the tenant's repo
      -> ONE commit with the template + a starter .github/workflows file,
         so build/test runs on the TENANT's Actions minutes, not ours
      -> register with code-intel as source_type="git" (git_url = their repo,
         git_ref = the new branch, auth_token = their PAT, decrypted
         just-in-time and never held past the request)
      -> optional generate() — unchanged Claude call; only its file-write
         step changed, from a local write to a commit on that branch
      -> open a PR

Nothing of ours previews the result. `preview.py`/`ingress.py` still exist
and still work, but only behind `APP_BUILDER_LOCAL_PREVIEW_ENABLED=true`
(default false) for bare local dev with no repo connected — see
`_create_project_local()` below and the contract's "Hard rules".

See `docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md`.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess

from . import audit, github_client, github_credentials, projects
from .codeintel_client import client as ci_client
from .github_client import GitHubClient, GitHubError
from .projects import Project, ProjectExistsError
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, project_root, validate_project_id


class TemplateNotFoundError(KeyError):
    pass


class LocalPreviewDisabledError(RuntimeError):
    """No GitHub repo is connected AND the legacy local-scaffold path is off
    (the default). Surfaced as 409 — the fix is to connect a repo, not to
    retry."""


def _code_intel_path(local_path: str, settings: Settings) -> str:
    """Translate a project checkout path from THIS service's filesystem into
    the path code-intel sees for the same bytes.

    LEGACY (`source_type="local"`) path only — reached solely when
    `APP_BUILDER_LOCAL_PREVIEW_ENABLED=true` and no GitHub repo is connected.
    The bring-your-own-repo path registers `source_type="git"` and shares no
    volume with code-intel at all, which removes the whole class of problem
    this translation exists to work around.

    code-intel registers local repos by absolute path and re-validates that
    the directory exists in its own container. app-builder and code-intel have
    separate volumes, so deployment mounts app-builder's data volume into
    code-intel read-only at ``settings.code_intel_repo_prefix``; this swaps the
    leading ``data_dir`` for that prefix. When the prefix is empty (bare local
    dev on a shared filesystem) the path is returned unchanged, so single-host
    behaviour is preserved.
    """
    prefix = settings.code_intel_repo_prefix
    if not prefix:
        return local_path
    data_dir = os.path.abspath(settings.data_dir)
    abs_path = os.path.abspath(local_path)
    rel = os.path.relpath(abs_path, data_dir)
    if rel.startswith(".."):
        # Path isn't under data_dir — don't guess; send it as-is and let
        # code-intel's own guard reject it loudly rather than fabricate a path.
        return local_path
    return os.path.join(prefix, rel).replace(os.sep, "/")


def _manifest_path(template_dir: str) -> str:
    return os.path.join(template_dir, ".appbuilder.json")


def list_templates(settings: Settings = SETTINGS) -> list[dict]:
    """Scan templates_dir for subdirectories carrying a `.appbuilder.json`
    manifest. Returns [{template_id, name, description}]."""
    root = settings.templates_dir
    if not os.path.isdir(root):
        return []
    out = []
    for entry in sorted(os.listdir(root)):
        tdir = os.path.join(root, entry)
        mpath = _manifest_path(tdir)
        if not os.path.isdir(tdir) or not os.path.isfile(mpath):
            continue
        with open(mpath, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        out.append({
            "template_id": entry,
            "name": manifest.get("name", entry),
            "description": manifest.get("description", ""),
        })
    return out


def get_template_manifest(template_id: str,
                          settings: Settings = SETTINGS) -> dict:
    """The `.appbuilder.json` manifest for one template — {name, install,
    run, port, image, build?, test?}. Raises TemplateNotFoundError if the
    template_id doesn't exist (or is a path-traversal attempt: joining
    against a validated allowlist below prevents escaping templates_dir)."""
    catalog = {t["template_id"] for t in list_templates(settings)}
    if template_id not in catalog:
        raise TemplateNotFoundError(template_id)
    tdir = os.path.join(settings.templates_dir, template_id)
    with open(_manifest_path(tdir), "r", encoding="utf-8") as f:
        return json.load(f)


def project_manifest(ctx: TenantContext, project_id: str,
                     settings: Settings = SETTINGS) -> dict:
    """The manifest as it currently lives in the PROJECT (not the original
    template), so a future AI-driven change to install/run/port commands is
    honored automatically.

    For a bring-your-own-repo project "the project" is a branch in the
    tenant's repo, so the manifest is read back from that branch — preserving
    exactly the property the old local-checkout read had. If it is missing or
    unreadable there we fall back to the template's own manifest rather than
    failing generate() over metadata. Legacy local projects are read from the
    checkout on disk, unchanged.
    """
    project = projects.get(settings.data_dir, ctx, project_id)
    if project is not None and project.github:
        return _byo_manifest(ctx, project, settings)

    pdir = project_root(settings.data_dir, ctx, project_id, create=False)
    mpath = _manifest_path(pdir)
    if not os.path.isfile(mpath):
        raise TemplateNotFoundError(project_id)
    with open(mpath, "r", encoding="utf-8") as f:
        return json.load(f)


def _byo_manifest(ctx: TenantContext, project: Project,
                  settings: Settings) -> dict:
    gh_state = project.github or {}
    try:
        conn = github_credentials.require(ctx, settings)
        token = github_credentials.token_for(conn, settings)
        text = GitHubClient(token, settings).get_file_text(
            gh_state.get("repo_full_name") or conn.repo_full_name,
            gh_state.get("branch") or conn.default_branch,
            ".appbuilder.json")
        if text:
            parsed = json.loads(text)
            if isinstance(parsed, dict):
                return parsed
    except (github_credentials.GitHubNotConnectedError,
            github_credentials.CredentialDecryptError,
            github_credentials.EncryptionNotConfiguredError,
            GitHubError, ValueError):
        pass
    # Imported repos carry a synthesized manifest on the record (nothing was
    # committed into the user's repo); see repo_import.py.
    stored = gh_state.get("manifest")
    if isinstance(stored, dict) and stored:
        return stored
    return get_template_manifest(project.template_id, settings)


# --------------------------------------------------------------------- #
# template -> in-memory file set

_SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "dist", "build"}

WORKFLOW_PATH = ".github/workflows/app-builder.yml"


def template_files(template_id: str,
                   settings: Settings = SETTINGS) -> list[dict]:
    """Read a template into memory as [{path, content}], content as raw
    bytes. Nothing is written to our filesystem — the whole point of the
    pivot is that a scaffold produces a commit, not a directory."""
    get_template_manifest(template_id, settings)  # validates the id, 404s early
    tdir = os.path.join(settings.templates_dir, template_id)
    out: list[dict] = []
    for dirpath, dirnames, filenames in os.walk(tdir):
        dirnames[:] = [d for d in dirnames if d not in _SKIP_DIRS]
        for fn in sorted(filenames):
            abs_path = os.path.join(dirpath, fn)
            rel = os.path.relpath(abs_path, tdir).replace(os.sep, "/")
            with open(abs_path, "rb") as f:
                out.append({"path": rel, "content": f.read()})
    return sorted(out, key=lambda f: f["path"])


def starter_workflow(manifest: dict, branch: str | None = None) -> str:
    """A starter GitHub Actions workflow, derived from the template's own
    manifest rather than hardcoded per stack: the manifest already declares a
    container image and the install/build/test commands, so `container:` plus
    plain `run:` steps covers every template with no per-stack branching.

    Runs on the TENANT's Actions minutes — the deliberate point of the pivot,
    and the file says so in its own header so nobody is surprised later.

    Two optional manifest keys extend it:

    * ``preview_dir`` — when set (and `branch` is known), a `pages` job
      deploys that build-output directory to the repo's own GitHub Pages
      site on every push to the app-builder branch: a real, free, public
      preview URL with zero extra signups. It only publishes from OUR
      branch so a tenant's unrelated pushes never redeploy the preview.
    * ``workflow_extra`` — verbatim job-level YAML appended under `jobs:`
      (used by the capacitor template for its Android/iOS build jobs).
      ``{branch}`` inside it is replaced with the app-builder branch.
    """
    image = (manifest.get("image") or "").strip()
    steps = ["      - uses: actions/checkout@v4"]
    for label, key in (("Install", "install"), ("Build", "build"),
                       ("Test", "test")):
        cmd = (manifest.get(key) or "").strip()
        if cmd:
            steps.append(f"      - name: {label}\n        run: {cmd}")
    if len(steps) == 1:
        steps.append("      - name: Nothing to build\n        run: echo "
                     "\"This template has no build or test step.\"")

    container = f"    container: {image}\n" if image else ""
    workflow = (
        "# Managed by VegaDūta app-builder.\n"
        "#\n"
        "# Build and test for this app run HERE, on this repository's own\n"
        "# GitHub Actions minutes — not on the VegaDūta platform. If a\n"
        "# `pages` job is present it publishes a free preview to this repo's\n"
        "# GitHub Pages site. Edit or delete this file freely; app-builder\n"
        "# only writes it once, when the project is first scaffolded.\n"
        "name: app-builder checks\n"
        "\n"
        "on:\n"
        "  push:\n"
        "    branches: [\"**\"]\n"
        "  workflow_dispatch:\n"
        "\n"
        "jobs:\n"
        "  check:\n"
        "    runs-on: ubuntu-latest\n"
        f"{container}"
        "    steps:\n"
        + "\n".join(steps) + "\n"
    )

    preview_dir = (manifest.get("preview_dir") or "").strip().strip("/")
    if preview_dir and branch:
        workflow += _pages_job(manifest, branch, preview_dir)

    extra = (manifest.get("workflow_extra") or "")
    if extra.strip() and branch:
        workflow += "\n" + extra.replace("{branch}", branch).rstrip() + "\n"

    return workflow


def _pages_job(manifest: dict, branch: str, preview_dir: str) -> str:
    """The `pages` deploy job. install/build steps from the manifest are
    repeated here so the artifact actually exists before upload — but on a
    plain runner with actions/setup-node rather than the manifest's
    container image, because JS actions (configure-pages, upload-pages-
    artifact, deploy-pages) need a Node runtime and ubuntu-latest provides
    one unconditionally."""
    steps = ["      - uses: actions/checkout@v4"]
    needs_node = any("npm" in (manifest.get(k) or "")
                     for k in ("install", "build", "test"))
    if needs_node:
        steps.append("      - uses: actions/setup-node@v4\n"
                     "        with:\n          node-version: 22")
    for label, key in (("Install", "install"), ("Build", "build")):
        cmd = (manifest.get(key) or "").strip()
        if cmd:
            steps.append(f"      - name: {label}\n        run: {cmd}")
    steps += [
        "      - uses: actions/configure-pages@v5",
        f"      - uses: actions/upload-pages-artifact@v3\n"
        f"        with:\n          path: {preview_dir}",
        "      - id: deployment\n        uses: actions/deploy-pages@v4",
    ]
    return (
        "\n"
        "  pages:\n"
        "    # Free preview: publishes the built app to this repository's own\n"
        "    # GitHub Pages site. Only the app-builder branch deploys, and\n"
        "    # enabling Pages is done once via the repo's Pages settings\n"
        "    # (app-builder attempts it automatically at scaffold time).\n"
        f"    if: github.ref == 'refs/heads/{branch}'\n"
        "    runs-on: ubuntu-latest\n"
        "    permissions:\n"
        "      pages: write\n"
        "      id-token: write\n"
        "    environment:\n"
        "      name: github-pages\n"
        "      url: ${{ steps.deployment.outputs.page_url }}\n"
        "    steps:\n"
        + "\n".join(steps) + "\n"
    )


# --------------------------------------------------------------------- #
# create

def create_project(ctx: TenantContext, project_id: str, template_id: str,
                   prompt: str | None = None,
                   settings: Settings = SETTINGS) -> Project:
    """Scaffold a project.

    Default path: push to a branch in the tenant's connected GitHub repo and
    open a PR there (see the module docstring). With no repo connected this
    fails with a clear 409 telling the caller to connect one — UNLESS
    `APP_BUILDER_LOCAL_PREVIEW_ENABLED=true`, in which case bare local dev
    falls back to the pre-pivot local-checkout behaviour, so this service
    stays runnable with no external dependency at all.
    """
    validate_project_id(project_id)
    if projects.get(settings.data_dir, ctx, project_id) is not None:
        raise ProjectExistsError(f"project already exists: {project_id}")

    manifest = get_template_manifest(template_id, settings)  # 404s early

    conn = github_credentials.load(ctx, settings)
    if conn is None:
        if not settings.local_preview_enabled:
            raise LocalPreviewDisabledError(
                "no GitHub repository is connected for this tenant. Connect "
                "one with POST /projects/github/connect — app-builder pushes "
                "generated code to a branch in your own repo and opens a pull "
                "request there.")
        return _create_project_local(ctx, project_id, template_id, prompt,
                                     settings)

    return _create_project_byo(ctx, project_id, template_id, prompt, manifest,
                               conn, settings)


def _create_project_byo(ctx: TenantContext, project_id: str, template_id: str,
                        prompt: str | None, manifest: dict,
                        conn: github_credentials.GitHubConnection,
                        settings: Settings) -> Project:
    repo = conn.repo_full_name
    base_branch = conn.default_branch or "main"
    branch = github_client.branch_for_project(project_id, settings)

    # Decrypted here and held ONLY for the duration of this call — never put
    # on the Project record, into the audit log, or into a response body.
    token = github_credentials.token_for(conn, settings)
    gh = GitHubClient(token, settings)

    base_sha = gh.get_ref_sha(repo, base_branch)
    if not base_sha:
        raise GitHubError(
            404, f"the default branch {base_branch!r} does not exist in "
                 f"{repo}. Push at least one commit to it, then retry.")
    if gh.get_ref_sha(repo, branch):
        raise ProjectExistsError(
            f"branch {branch!r} already exists in {repo} — delete it or pick "
            "a different project id")

    files = template_files(template_id, settings)
    files.append({"path": WORKFLOW_PATH,
                  "content": starter_workflow(manifest, branch)})

    gh.create_branch(repo, branch, base_sha)
    commit_sha = gh.commit_files(
        repo, branch, files, f"Scaffold from template {template_id}")

    project = Project(
        project_id=project_id, tenant_id=ctx.tenant_id,
        template_id=template_id, status="scaffolding",
        code_intel_repo_id=project_id,
        github={
            "repo_full_name": repo,
            "repo_url": f"https://github.com/{repo}",
            "base_branch": base_branch,
            "branch": branch,
            "branch_url": f"https://github.com/{repo}/tree/{branch}",
            "commit_sha": commit_sha,
            "pr_number": None,
            "pr_url": None,
        })
    projects.upsert(settings.data_dir, ctx, project)

    tdir = _tenant_dir(ctx, settings)
    audit.record(tdir, {"kind": "project_scaffolded", "project_id": project_id,
                        "template_id": template_id, "actor": ctx.subject,
                        "had_prompt": bool(prompt), "repo_full_name": repo,
                        "branch": branch, "commit_sha": commit_sha})

    try:
        # source_type="git": code-intel clones straight from GitHub with the
        # tenant's own token. No shared volume with app-builder at all — the
        # cross-container path translation _code_intel_path() exists for is
        # simply not in play here.
        ci_client.register_repo(
            ctx, project_id, source_type="git",
            git_url=f"https://github.com/{repo}.git", git_ref=branch,
            auth_token=token)
        ci_client.index_repo(ctx, project_id)
    except Exception as e:
        project.status = "error"
        project.error = f"code-intel registration failed: {e}"[:500]
        projects.upsert(settings.data_dir, ctx, project)
        raise

    project.status = "ready"
    projects.upsert(settings.data_dir, ctx, project)

    if prompt:
        from . import generate
        try:
            generate.modify_project(ctx, project_id, prompt, settings=settings)
        except Exception as e:
            # The branch is pushed and reviewable as-is; the initial-prompt
            # iteration is best-effort. Surface the failure on the record
            # rather than losing it, but don't fail scaffold creation over it.
            project = projects.get_or_404(settings.data_dir, ctx, project_id)
            project.error = f"initial prompt generate() failed: {e}"[:500]
            projects.upsert(settings.data_dir, ctx, project)

    _enable_pages(ctx, project_id, manifest, gh, settings)
    _open_pull_request(ctx, project_id, template_id, prompt, gh, settings)
    return projects.get_or_404(settings.data_dir, ctx, project_id)


def _enable_pages(ctx: TenantContext, project_id: str, manifest: dict,
                  gh: GitHubClient, settings: Settings) -> None:
    """Best-effort: switch on the repo's GitHub Pages site (Actions source)
    so the scaffolded `pages` job can publish a free preview. Recorded on
    the project's github state as `pages_url` + `pages_enabled`; a token
    without Pages:write leaves `pages_enabled` false and the workflow's
    pages job simply fails with GitHub's own error until the tenant enables
    it manually — never a scaffold failure."""
    if not (manifest.get("preview_dir") or "").strip():
        return
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    gh_state = dict(project.github or {})
    repo = gh_state.get("repo_full_name")
    if not repo:
        return
    gh_state["pages_url"] = github_client.pages_url(repo)
    try:
        gh_state["pages_enabled"] = bool(
            gh.enable_pages(repo).get("enabled"))
    except GitHubError:
        gh_state["pages_enabled"] = False
    project.github = gh_state
    projects.upsert(settings.data_dir, ctx, project)
    audit.record(_tenant_dir(ctx, settings), {
        "kind": "pages_enable", "project_id": project_id,
        "actor": ctx.subject, "repo_full_name": repo,
        "pages_url": gh_state["pages_url"],
        "enabled": gh_state["pages_enabled"]})


def _open_pull_request(ctx: TenantContext, project_id: str, template_id: str,
                       prompt: str | None, gh: GitHubClient,
                       settings: Settings) -> None:
    """Open the PR LAST, so it already contains the initial generate() commit.

    Best-effort by design: the branch is pushed, which is the real work, and
    the tenant can always open the PR themselves from GitHub's UI. A failure
    here is recorded on the project instead of unwinding an otherwise
    successful scaffold into an inconsistent half-state.
    """
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    gh_state = dict(project.github or {})
    repo = gh_state.get("repo_full_name")
    branch = gh_state.get("branch")
    if not repo or not branch:
        return

    pages_url = gh_state.get("pages_url")
    pages_note = ""
    if pages_url and gh_state.get("pages_enabled"):
        pages_note = (
            f"\n\n**Live preview:** the `pages` job in `{WORKFLOW_PATH}` "
            "publishes every push to this branch to this repo's GitHub "
            f"Pages site — {pages_url} (allow a minute or two after the "
            "first deploy).")
    elif pages_url:
        pages_note = (
            "\n\n**Preview:** this project wants to publish to GitHub Pages "
            f"({pages_url}) but the connected token cannot manage Pages. "
            "Enable it once under Settings → Pages → 'GitHub Actions', or "
            "reconnect with a token that has Pages: read/write.")

    body = (
        f"Scaffolded by VegaDūta app-builder from the `{template_id}` "
        "template.\n\n"
        + (f"**What was asked for:** {prompt[:1000]}\n\n" if prompt else "")
        + "Build and test for this branch run on **this repository's own "
          "GitHub Actions minutes**, not on the VegaDūta platform — see "
          f"`{WORKFLOW_PATH}`. If you have a deploy-preview provider wired up "
          "(Vercel, Netlify, Cloudflare Pages), its preview for this PR is "
          "the app running." + pages_note + "\n\n"
          "Review and merge as you would any other pull request."
    )
    try:
        pr = gh.create_pull(
            repo, head=branch, base=gh_state.get("base_branch") or "main",
            title=f"app-builder: {project_id}", body=body)
    except GitHubError as e:
        project.error = (
            f"branch pushed, but opening the pull request failed: {e}")[:500]
        projects.upsert(settings.data_dir, ctx, project)
        return

    gh_state["pr_number"] = pr.get("pr_number")
    gh_state["pr_url"] = pr.get("pr_url")
    project.github = gh_state
    projects.upsert(settings.data_dir, ctx, project)

    audit.record(_tenant_dir(ctx, settings), {
        "kind": "pull_request_opened", "project_id": project_id,
        "actor": ctx.subject, "repo_full_name": repo, "branch": branch,
        "pr_number": pr.get("pr_number"), "pr_url": pr.get("pr_url")})


def _create_project_local(ctx: TenantContext, project_id: str,
                          template_id: str, prompt: str | None,
                          settings: Settings) -> Project:
    """The pre-pivot local-checkout scaffold, behaviourally unchanged.

    Reachable ONLY with `APP_BUILDER_LOCAL_PREVIEW_ENABLED=true` and no
    connected GitHub repo, so bare local dev keeps working with zero external
    dependencies. Production tenants never reach this, and no production code
    path starts a Docker preview container any more.
    """
    template_dir = os.path.join(settings.templates_dir, template_id)

    pdir = project_root(settings.data_dir, ctx, project_id)
    # project_root() already created pdir via makedirs(exist_ok=True); copy
    # the template INTO it rather than copytree-ing over it (copytree
    # refuses to target an existing directory).
    shutil.copytree(template_dir, pdir, dirs_exist_ok=True)

    _run_git(["init", "-q"], pdir)
    _run_git(["config", "user.email", "app-builder@example.com"], pdir)
    _run_git(["config", "user.name", "app-builder"], pdir)
    _run_git(["add", "-A"], pdir)
    _run_git(["commit", "-q", "-m", f"Scaffold from template {template_id}"], pdir)

    project = Project(
        project_id=project_id, tenant_id=ctx.tenant_id,
        template_id=template_id, status="scaffolding",
        code_intel_repo_id=project_id)
    projects.upsert(settings.data_dir, ctx, project)

    tdir = _tenant_dir(ctx, settings)
    audit.record(tdir, {"kind": "project_scaffolded", "project_id": project_id,
                        "template_id": template_id, "actor": ctx.subject,
                        "had_prompt": bool(prompt), "local_fallback": True})

    try:
        ci_client.register_repo(
            ctx, project_id, source_type="local",
            path=_code_intel_path(pdir, settings))
        ci_client.index_repo(ctx, project_id)
    except Exception as e:
        project.status = "error"
        project.error = f"code-intel registration failed: {e}"[:500]
        projects.upsert(settings.data_dir, ctx, project)
        raise

    project.status = "ready"
    projects.upsert(settings.data_dir, ctx, project)

    if prompt:
        from . import generate
        try:
            generate.modify_project(ctx, project_id, prompt, settings=settings)
        except Exception as e:
            project.error = f"initial prompt generate() failed: {e}"[:500]
            projects.upsert(settings.data_dir, ctx, project)

    return projects.get_or_404(settings.data_dir, ctx, project_id)


def _run_git(args: list[str], cwd: str) -> None:
    """Legacy local-scaffold path only. The bring-your-own-repo flow never
    shells out to git: it writes through GitHub's Git Data API — see
    github_client.py's module docstring for why."""
    subprocess.run(["git", *args], cwd=cwd, check=True,
                   capture_output=True, text=True, timeout=60)


def _tenant_dir(ctx: TenantContext, settings: Settings) -> str:
    from .tenancy import tenant_root
    return tenant_root(settings.data_dir, ctx)


def delete_project(ctx: TenantContext, project_id: str,
                   settings: Settings = SETTINGS) -> bool:
    """Removes the registry record, any legacy local checkout, and
    (best-effort) the code-intel registration. Never raises on the code-intel
    side — a project the operator can no longer reach code-intel for should
    still be deletable locally.

    Deliberately does NOT delete the branch or close the PR in the tenant's
    repository: that is their code in their repo, and destroying it from here
    would be the wrong default. The PR link stays valid after deletion.
    """
    existed = projects.get(settings.data_dir, ctx, project_id) is not None
    if not existed:
        return False

    try:
        ci_client.delete_repo(ctx, project_id)
    except Exception:
        pass

    pdir = project_root(settings.data_dir, ctx, project_id, create=False)
    shutil.rmtree(pdir, ignore_errors=True)

    projects.delete(settings.data_dir, ctx, project_id)
    audit.record(_tenant_dir(ctx, settings), {
        "kind": "project_deleted", "project_id": project_id,
        "actor": ctx.subject})
    return True
