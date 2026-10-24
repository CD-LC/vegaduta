"""Import an EXISTING GitHub repo as an app-builder project.

Scaffolding (scaffold.py) creates a branch from a template. Importing does the
opposite: the tenant already has code, so we register it as a project WITHOUT
writing anything into their repo:

  inspect the tree  ->  detect the stack  ->  synthesize a manifest
  (persisted on the project record, NOT committed)  ->  create the
  app-builder branch from the base branch  ->  register + index in code-intel.

The manifest lives in `project.github["manifest"]`. scaffold._byo_manifest
prefers a `.appbuilder.json` on the branch if the user commits one, then this
stored manifest, then the template fallback.

Detection and the size caps are PURE functions (no I/O) so they are unit
tested directly; `import_project` is the only function that talks to GitHub.

Limits, stated plainly:
* `subdir` scopes detection and the manifest commands (`cd <subdir> && ...`),
  but code-intel still indexes the whole repo at branch level and generate
  paths stay repo-root relative.
* The recursive tree API truncates for enormous repos; an over-cap tree is
  refused (413) rather than partially scanned.
"""

from __future__ import annotations

import json
import re
import shlex
from typing import Callable

from . import audit, github_client, github_credentials, projects
from .codeintel_client import client as ci_client
from .github_client import GitHubClient, GitHubError, safe_repo_path
from .projects import Project, ProjectExistsError
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root, validate_project_id

SKIP_DIRS = frozenset(
    {
        "node_modules",
        "dist",
        "build",
        ".git",
        ".next",
        ".nuxt",
        ".svelte-kit",
        ".venv",
        "venv",
        "__pycache__",
        "target",
        "vendor",
        ".gradle",
        "Pods",
        ".idea",
        ".cache",
        "coverage",
        ".turbo",
    }
)

BINARY_EXTS = frozenset(
    {
        "png",
        "jpg",
        "jpeg",
        "gif",
        "webp",
        "ico",
        "bmp",
        "tiff",
        "pdf",
        "zip",
        "gz",
        "tgz",
        "tar",
        "7z",
        "rar",
        "jar",
        "war",
        "class",
        "exe",
        "dll",
        "so",
        "dylib",
        "bin",
        "o",
        "a",
        "woff",
        "woff2",
        "ttf",
        "otf",
        "eot",
        "mp3",
        "mp4",
        "mov",
        "avi",
        "webm",
        "wav",
        "ogg",
        "apk",
        "aab",
        "ipa",
        "keystore",
        "jks",
        "sqlite",
        "db",
        "psd",
        "sketch",
        "fig",
    }
)


# `/`-separated segments of plain path characters, matched with fullmatch (a "$"
# anchor also accepts one trailing newline). The first character may not be "-":
# `cd -x` would be read as an option, not a directory.
_SUBDIR_RX = re.compile(r"[A-Za-z0-9._][A-Za-z0-9._\-]*(/[A-Za-z0-9._\-]+)*")


class ImportTooLargeError(RuntimeError):
    """The repo (after skipping vendored/binary files) exceeds the caps."""


class StackNotDetectedError(RuntimeError):
    """Nothing in the tree looked like a supported app."""


# --------------------------------------------------------------------- #
# pure helpers


def scope_entries(entries: list[dict], subdir: str | None) -> list[dict]:
    """Restrict a tree listing to `subdir` (paths stay repo-root relative)."""
    if not subdir:
        return list(entries)
    prefix = subdir.strip("/") + "/"
    return [e for e in entries if (e.get("path") or "").startswith(prefix)]


def filter_tree(entries: list[dict], max_files: int, max_bytes: int) -> tuple[list[dict], dict]:
    """Drop vendored/build dirs and binaries, enforce the caps.

    Returns (kept, stats). Raises ImportTooLargeError over either cap. Only
    KEPT files count toward the caps, so a repo with a committed
    node_modules of 40k files is fine but 6k real source files is not."""
    kept: list[dict] = []
    skipped_dirs = skipped_binary = 0
    total = 0
    for e in entries:
        path = e.get("path") or ""
        parts = path.split("/")
        if any(p in SKIP_DIRS for p in parts[:-1]):
            skipped_dirs += 1
            continue
        ext = parts[-1].rsplit(".", 1)[-1].lower() if "." in parts[-1] else ""
        if ext in BINARY_EXTS:
            skipped_binary += 1
            continue
        kept.append(e)
        total += int(e.get("size") or 0)
        if len(kept) > max_files:
            raise ImportTooLargeError(
                f"the repository has more than {max_files} source files "
                "after skipping node_modules/dist/binaries. Import a "
                "sub-folder with `subdir`, or raise "
                "APP_BUILDER_IMPORT_MAX_FILES."
            )
        if total > max_bytes:
            raise ImportTooLargeError(
                f"the repository source is larger than {max_bytes} bytes "
                "after skipping node_modules/dist/binaries. Import a "
                "sub-folder with `subdir`, or raise "
                "APP_BUILDER_IMPORT_MAX_BYTES."
            )
    return kept, {
        "files": len(kept),
        "bytes": total,
        "skipped_vendored": skipped_dirs,
        "skipped_binary": skipped_binary,
    }


def _install_cmd(names: set[str]) -> str:
    if "pnpm-lock.yaml" in names:
        return "npm install -g pnpm && pnpm install"
    if "yarn.lock" in names:
        return "yarn install --frozen-lockfile"
    if "package-lock.json" in names:
        return "npm ci"
    return "npm install"


def _test_cmd(scripts: dict) -> str:
    t = (scripts.get("test") or "").strip()
    if not t or "no test specified" in t:
        return ""
    # vitest/jest watch by default in a TTY; --run keeps CI one-shot.
    return "npm test -- --run" if "vitest" in t else "npm test"


def detect_stack(names: set[str], read: Callable[[str], str | None]) -> dict:
    """Detect the stack from root-level file names (relative to the project
    root) and return a manifest dict shaped like a template's
    `.appbuilder.json` plus `stack` and `detected: true`.

    `names` is the set of ALL relative paths; `read(rel)` returns file text or
    None. Raises StackNotDetectedError."""
    root_files = {n for n in names if "/" not in n}

    has_pkg = "package.json" in root_files
    pkg: dict = {}
    if has_pkg:
        try:
            parsed = json.loads(read("package.json") or "{}")
            pkg = parsed if isinstance(parsed, dict) else {}
        except ValueError:
            pkg = {}
    deps = {**(pkg.get("devDependencies") or {}), **(pkg.get("dependencies") or {})}
    scripts = pkg.get("scripts") or {}

    def node(
        stack: str, name: str, port: int, dev_flags: str, preview_dir: str | None = "dist"
    ) -> dict:
        dev = "npm run dev" + (f" -- {dev_flags}" if dev_flags else "")
        run = "npm start" if ("dev" not in scripts and "start" in scripts) else dev
        m = {
            "name": name,
            "stack": stack,
            "detected": True,
            "install": _install_cmd(root_files),
            "run": run,
            "build": "npm run build" if "build" in scripts else "",
            "test": _test_cmd(scripts),
            "port": port,
            "image": "node:20-bookworm",
        }
        if preview_dir and "build" in scripts:
            m["preview_dir"] = preview_dir
        return m

    has_cap_cfg = any(re.fullmatch(r"capacitor\.config\.(ts|js|json|mjs)", n) for n in root_files)
    if has_pkg and (has_cap_cfg or "@capacitor/core" in deps):
        return node("capacitor-app", "Mobile App (Capacitor)", 5173, "--host 0.0.0.0 --port 5173")
    if has_pkg and (
        any(re.fullmatch(r"astro\.config\.(mjs|ts|js|cjs)", n) for n in root_files)
        or "astro" in deps
    ):
        return node("astro-site", "Astro Website", 4321, "--host 0.0.0.0 --port 4321")
    if (
        has_pkg
        and "react" in deps
        and ("vite" in deps or any(re.match(r"^vite\.config\.", n) for n in root_files))
    ):
        return node("node-react", "Node + React (Vite)", 3000, "--host 0.0.0.0 --port 3000")
    if has_pkg and "react" in deps:
        return node("node-react", "Node + React", 3000, "", preview_dir=None)
    if has_pkg:
        return node("node", "Node.js", 3000, "", preview_dir=None)

    py_text = ""
    for f in ("requirements.txt", "pyproject.toml"):
        if f in root_files:
            py_text += (read(f) or "").lower() + "\n"
    if "fastapi" in py_text:
        target = (
            "main:app"
            if "main.py" in root_files
            else "app.main:app"
            if "app/main.py" in names
            else "main:app"
        )
        install = (
            "pip install -r requirements.txt"
            if "requirements.txt" in root_files
            else "pip install ."
        )
        return {
            "name": "Python + FastAPI",
            "stack": "python-fastapi",
            "detected": True,
            "install": install,
            "run": f"uvicorn {target} --host 0.0.0.0 --port 8000",
            "build": "",
            "test": "pytest -q" if "pytest" in py_text else "",
            "port": 8000,
            "image": "python:3.12-slim",
        }

    if "index.html" in root_files:
        return {
            "name": "Static HTML",
            "stack": "static-html",
            "detected": True,
            "install": "",
            "run": "python -m http.server 8000",
            "build": "",
            "test": "",
            "port": 8000,
            "image": "python:3.12-slim",
            "preview_dir": ".",
        }

    raise StackNotDetectedError(
        "could not detect a supported stack (looked for package.json with "
        "React/Astro/Capacitor, a FastAPI requirements.txt/pyproject.toml, or "
        "an index.html). If the app lives in a sub-folder, pass `subdir`."
    )


def validate_subdir(subdir: str) -> str:
    """The normalized sub-folder, or GitHubError(400).

    The value is interpolated into `cd <sub> && ...` shell commands (and from
    there into a generated GitHub Actions workflow), so only plain path
    characters are allowed: no whitespace or other control character (a newline
    would start a second command), no `;` `$` backtick `|` `&` and so on.
    """
    sub = safe_repo_path(subdir).strip("/")
    if any(ord(c) < 0x20 or ord(c) == 0x7F for c in sub) or not _SUBDIR_RX.fullmatch(sub):
        raise GitHubError(
            400,
            "subdir may only contain letters, digits, '.', '_', '-' and '/', and may not "
            "start with '-'.",
        )
    return sub


def scope_manifest(manifest: dict, subdir: str | None) -> dict:
    """Prefix every command with `cd <subdir> &&` for a sub-folder import."""
    if not subdir:
        return manifest
    d = validate_subdir(subdir)
    out = dict(manifest)
    for k in ("install", "run", "build", "test"):
        if out.get(k):
            out[k] = f"cd {shlex.quote(d)} && {out[k]}"
    pd = out.get("preview_dir")
    if pd:
        out["preview_dir"] = d if pd == "." else f"{d}/{pd}"
    out["subdir"] = d
    return out


def derive_project_id(repo_full_name: str, subdir: str | None, taken: set[str]) -> str:
    base = repo_full_name.split("/", 1)[1]
    if subdir:
        base += "-" + subdir.strip("/").replace("/", "-")
    base = re.sub(r"[^A-Za-z0-9._\-]", "-", base).strip("-.")[:100] or "imported"
    cand, n = base, 2
    while cand in taken:
        cand = f"{base}-{n}"
        n += 1
    return cand


# --------------------------------------------------------------------- #
# the one function that does I/O


def import_project(
    ctx: TenantContext,
    repo_full_name: str,
    base_branch: str | None = None,
    subdir: str | None = None,
    project_id: str | None = None,
    settings: Settings = SETTINGS,
) -> Project:
    name = github_client.validate_repo_full_name(repo_full_name)
    sub = None
    if subdir and subdir.strip("/"):
        sub = validate_subdir(subdir)

    conn = github_credentials.require(ctx, settings)
    token = github_credentials.token_for(conn, settings)
    gh = GitHubClient(token, settings)

    repo = gh.get_repo(name)
    perms = repo.get("permissions")
    if isinstance(perms, dict) and not perms.get("push"):
        raise GitHubError(
            403,
            f"this token can read {name} but not write to "
            "it; the builder needs Contents: read/write "
            "to create its branch.",
        )
    base = github_client.validate_branch(base_branch or repo.get("default_branch") or "main")
    base_sha = gh.get_ref_sha(name, base)
    if not base_sha:
        raise GitHubError(404, f"branch {base!r} does not exist in {name}.")

    entries = scope_entries(gh.list_tree(name, base), sub)
    if sub and not entries:
        raise StackNotDetectedError(f"no files found under {sub!r} on {base}.")
    kept, stats = filter_tree(entries, settings.import_max_files, settings.import_max_bytes)

    strip = (sub + "/") if sub else ""
    rel_names = {e["path"][len(strip) :] for e in kept}
    manifest = scope_manifest(
        detect_stack(rel_names, lambda rel: gh.get_file_text(name, base, strip + rel)), sub
    )

    existing = projects.load(settings.data_dir, ctx)
    if project_id:
        validate_project_id(project_id)
        if project_id in existing:
            raise ProjectExistsError(f"project already exists: {project_id}")
        pid = project_id
    else:
        pid = derive_project_id(name, sub, set(existing))

    branch = github_client.branch_for_project(pid, settings)
    if gh.get_ref_sha(name, branch):
        raise ProjectExistsError(
            f"branch {branch!r} already exists in {name} - delete it or pick a different project id"
        )
    gh.create_branch(name, branch, base_sha)

    project = Project(
        project_id=pid,
        tenant_id=ctx.tenant_id,
        template_id=f"import-{manifest['stack']}",
        status="scaffolding",
        code_intel_repo_id=pid,
        github={
            "repo_full_name": name,
            "repo_url": f"https://github.com/{name}",
            "base_branch": base,
            "branch": branch,
            "branch_url": f"https://github.com/{name}/tree/{branch}",
            "commit_sha": base_sha,
            "pr_number": None,
            "pr_url": None,
            "imported": True,
            "subdir": sub,
            "manifest": manifest,
            "import_stats": stats,
        },
    )
    projects.upsert(settings.data_dir, ctx, project)
    audit.record(
        tenant_root(settings.data_dir, ctx),
        {
            "kind": "project_imported",
            "project_id": pid,
            "actor": ctx.subject,
            "repo_full_name": name,
            "base_branch": base,
            "branch": branch,
            "stack": manifest["stack"],
            "subdir": sub,
            "files": stats["files"],
        },
    )

    try:
        ci_client.register_repo(
            ctx,
            pid,
            source_type="git",
            git_url=f"https://github.com/{name}.git",
            git_ref=branch,
            auth_token=token,
        )
        ci_client.index_repo(ctx, pid)
    except Exception as e:
        project.status = "error"
        project.error = f"code-intel registration failed: {e}"[:500]
        projects.upsert(settings.data_dir, ctx, project)
        raise
    project.status = "ready"
    projects.upsert(settings.data_dir, ctx, project)
    return project
