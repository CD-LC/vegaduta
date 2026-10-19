"""Thin GitHub REST client — branch, commit, PR, and token validation.

Part of the bring-your-own-repo pivot
(`docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md`): generated code lands on
a branch in the TENANT's own repository and a PR is opened there, instead of
being written to a checkout on this host and previewed in a container we pay
for.

## Why the Git Data API and not a short-lived clone+push

The contract leaves this call to this scope. This module uses GitHub's Git
Data API (blobs -> tree -> commit -> update-ref), i.e. the low-level sibling
of the Contents API, rather than `git clone && git push` into a temp dir:

* **Nothing is written to our filesystem at all.** A clone+push writes the
  tenant's full source tree to disk and then deletes it — and if the process
  dies mid-flow (OOM on a shared 8vCPU box is exactly the pressure that
  motivated this pivot), it leaves that tree and a running credential-helper
  `git` behind. "No local checkout" is a stronger, testable property than
  "a local checkout we promise to delete", and it is what the acceptance
  test for this pivot asserts.
* **One atomic commit for N files.** The naive Contents API
  (`PUT /contents/{path}`) makes one commit per file — six commits for a
  six-file template, with no atomicity and six times the rate-limit cost.
  Blobs -> tree -> commit gets a single commit for any file count.
* **No `git` binary, no credential helper, no temp-dir cleanup race.** The
  PAT exists only as an `Authorization` header on one in-flight request; it
  never reaches argv, a subprocess environment, `.git/config`, or disk.

The tradeoff, stated plainly: a very large generated file set costs one blob
POST per file. Template scaffolds and per-iteration AI edits are single-digit
to low-tens of files, so this is the right shape here; a future bulk import
of thousands of files would want a packfile push instead.

Note this is a *different* job from `code-intel/app/gitops.py`, which we
deliberately do NOT duplicate: gitops.py *reads* repos (clone/fetch for
indexing) and keeps its ephemeral-credential-helper clone path as the one
git client in the platform. This module only *writes* — and does so over
HTTP, never git.

## Secret hygiene

`GitHubError` messages are built from GitHub's own `message` field and are
scrubbed of the token before being raised, so a PAT can never surface in an
API response, a log line, or a traceback. Instances hold the token for the
duration of a single request and must not be cached across requests — see
`github_credentials.token_for()`'s docstring.
"""

from __future__ import annotations

import base64
import json
import re

import httpx

from .settings import SETTINGS, Settings

# owner/repo, GitHub's own allowed character set. Validated before it is
# interpolated into any API path so a crafted value cannot traverse into a
# different endpoint (e.g. "a/b/../../user").
_REPO_FULL_NAME_RX = re.compile(
    r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,99}/[A-Za-z0-9][A-Za-z0-9._\-]{0,99}$")
# git branch names we are willing to create. Deliberately narrower than git's
# own rules (no "/" beyond the prefix separator handled by the caller, no
# "..", no leading "-") for the same interpolation-safety reason.
_BRANCH_RX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._\-]{0,100}"
                        r"(/[A-Za-z0-9][A-Za-z0-9._\-]{0,100})*$")

_API_VERSION = "2022-11-28"
_BLOB_MODE = "100644"

# Identity stamped on every commit app-builder creates through the Git Data
# API. Without it GitHub attributes API commits to the PAT owner, which makes
# "edited outside the builder" undetectable. Merge commits made by the merge
# API cannot carry an author, so they are recognised by BUILDER_MESSAGE_PREFIX.
BUILDER_AUTHOR_NAME = "VegaDuta App Builder"
BUILDER_AUTHOR_EMAIL = "app-builder@example.com"
BUILDER_MESSAGE_PREFIX = "app-builder:"




class GitHubError(RuntimeError):
    """Any non-2xx from GitHub, or a transport failure. `status` mirrors the
    HTTP status (0 for pure transport failures) so main.py can propagate
    something meaningful instead of a blanket 500. The message is always
    token-scrubbed."""

    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


class MergeConflictError(GitHubError):
    """The merge API answered 409. `paths` lists files changed on both sides
    (best effort; GitHub does not return them)."""

    def __init__(self, message: str, paths: list[str] | None = None):
        super().__init__(409, message)
        self.paths = paths or []


class InvalidRepoNameError(ValueError):
    pass


def validate_repo_full_name(repo_full_name: str) -> str:
    name = (repo_full_name or "").strip().strip("/")
    if ".." in name or not _REPO_FULL_NAME_RX.match(name):
        raise InvalidRepoNameError(
            f"not a valid GitHub repository (expected owner/repo): "
            f"{repo_full_name!r}")
    return name


def validate_branch(branch: str) -> str:
    b = (branch or "").strip()
    if ".." in b or not _BRANCH_RX.match(b):
        raise InvalidRepoNameError(f"not a valid branch name: {branch!r}")
    return b


def pages_url(repo_full_name: str) -> str:
    """The canonical GitHub Pages URL for a repo
    (https://<owner>.github.io/<repo>/). Deterministic — correct whether or
    not the site is enabled yet, so it can be shown as "preview (pending
    first deploy)"."""
    name = validate_repo_full_name(repo_full_name)
    owner, repo = name.split("/", 1)
    return f"https://{owner}.github.io/{repo}/"


def branch_for_project(project_id: str, settings: Settings = SETTINGS) -> str:
    """`<APP_BUILDER_GITHUB_BRANCH_PREFIX>/<project_id>`. project_id is
    already validated by tenancy.validate_project_id() before it reaches
    here; the result is re-validated anyway (cheap, and keeps this function
    safe to call from anywhere)."""
    prefix = (settings.github_branch_prefix or "app-builder").strip("/")
    return validate_branch(f"{prefix}/{project_id}")


def redact(text: str, token: str | None) -> str:
    """Replace a token with a fixed placeholder anywhere it appears. Applied
    to every message this module raises, so no code path can leak a PAT into
    an exception, a response body, or a log line."""
    if not text:
        return ""
    if token:
        for candidate in {token, token.strip()}:
            if candidate:
                text = text.replace(candidate, "***")
    return text


class GitHubClient:
    """Request-scoped GitHub client. Construct it, use it, drop it — never
    cache one on a module global or a long-lived object, because it holds a
    tenant's plaintext PAT in memory."""

    def __init__(self, token: str, settings: Settings = SETTINGS) -> None:
        self._token = token
        self.settings = settings

    # ------------------------------------------------------------------ #
    # transport

    def _headers(self) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self._token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": _API_VERSION,
            "Content-Type": "application/json",
            "User-Agent": "vegaduta-app-builder",
        }

    def _url(self, path: str) -> str:
        return self.settings.github_api_url.rstrip("/") + path

    def _request(self, method: str, path: str, *,
                 json_body: dict | None = None,
                 params: dict | None = None,
                 allow_404: bool = False,
                 allow_empty_repo: bool = False) -> dict | None:
        url = self._url(path)
        try:
            resp = httpx.request(
                method, url, headers=self._headers(), json=json_body,
                params=params, timeout=self.settings.github_timeout_seconds)
        except httpx.TimeoutException as e:
            raise GitHubError(504, redact(
                f"GitHub request timed out: {method} {path}", self._token)) from e
        except httpx.TransportError as e:
            raise GitHubError(0, redact(
                f"GitHub unreachable at {url}: {e}", self._token)) from e

        if resp.status_code == 404 and allow_404:
            return None
        # A repo with zero commits on ANY branch (freshly created with no
        # README/initial commit) returns 409 "Git Repository is empty" for
        # git-data ref lookups, not 404 - GitHub only 404s a ref lookup once
        # the repo has at least one commit but not that particular branch.
        # Callers that already treat "ref missing" as a normal, handleable
        # case (get_ref_sha) need this folded into the same None-return path
        # rather than surfacing as a raw GitHubError.
        if resp.status_code == 409 and allow_empty_repo:
            return None
        if resp.status_code >= 400:
            raise GitHubError(resp.status_code, redact(
                f"GitHub {resp.status_code} on {method} {path}: "
                f"{self._error_message(resp)}", self._token))
        if not resp.content:
            return {}
        try:
            body = resp.json()
        except ValueError:
            return {}
        return body if isinstance(body, dict) else {"items": body}

    @staticmethod
    def _error_message(resp: httpx.Response) -> str:
        try:
            body = resp.json()
        except ValueError:
            return (resp.text or "")[:300]
        if isinstance(body, dict):
            msg = str(body.get("message") or "")
            errors = body.get("errors")
            if errors:
                msg = f"{msg} ({json.dumps(errors)[:200]})"
            return msg[:300]
        return str(body)[:300]

    # ------------------------------------------------------------------ #
    # repo / validation

    def get_repo(self, repo_full_name: str) -> dict:
        name = validate_repo_full_name(repo_full_name)
        return self._request("GET", f"/repos/{name}") or {}

    # ------------------------------------------------------------------ #
    # refs

    def get_ref_sha(self, repo_full_name: str, branch: str) -> str | None:
        """Commit sha at the tip of `branch`, or None if the branch does not
        exist (including when the repo has no commits at all yet)."""
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(branch)
        body = self._request("GET", f"/repos/{name}/git/ref/heads/{ref}",
                             allow_404=True, allow_empty_repo=True)
        if not body:
            return None
        return ((body.get("object") or {}).get("sha")) or None

    def create_branch(self, repo_full_name: str, branch: str,
                      from_sha: str) -> str:
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(branch)
        body = self._request("POST", f"/repos/{name}/git/refs", json_body={
            "ref": f"refs/heads/{ref}", "sha": from_sha}) or {}
        return ((body.get("object") or {}).get("sha")) or from_sha

    def _update_ref(self, repo_full_name: str, branch: str, sha: str) -> None:
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(branch)
        # force=False: a fast-forward-only ref update. If someone else moved
        # the branch since we read its tip we want GitHub's 422, not a silent
        # overwrite of the tenant's commit.
        self._request("PATCH", f"/repos/{name}/git/refs/heads/{ref}",
                      json_body={"sha": sha, "force": False})

    # ------------------------------------------------------------------ #
    # commit N files as ONE commit (blobs -> tree -> commit -> ref)

    def commit_files(self, repo_full_name: str, branch: str,
                     files: list[dict], message: str) -> str:
        """Commit `files` ([{path, content}], content str or bytes) onto the
        tip of `branch` as a single commit. Returns the new commit sha.

        Files not listed are untouched: the new tree is built with
        `base_tree` set to the parent commit's tree, so this is an overlay,
        never a replacement of the branch contents.
        """
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(branch)
        if not files:
            raise GitHubError(400, "commit_files called with no files")

        parent_sha = self.get_ref_sha(name, ref)
        if not parent_sha:
            raise GitHubError(404, f"branch does not exist: {ref}")
        parent = self._request("GET", f"/repos/{name}/git/commits/{parent_sha}") or {}
        base_tree = (parent.get("tree") or {}).get("sha")

        tree_entries = []
        for f in files:
            path = safe_repo_path(f["path"])
            blob = self._request("POST", f"/repos/{name}/git/blobs", json_body={
                "content": _b64(f.get("content", "")),
                "encoding": "base64"}) or {}
            blob_sha = blob.get("sha")
            if not blob_sha:
                raise GitHubError(502, f"GitHub returned no blob sha for {path}")
            tree_entries.append({"path": path, "mode": _BLOB_MODE,
                                 "type": "blob", "sha": blob_sha})

        tree_body: dict = {"tree": tree_entries}
        if base_tree:
            tree_body["base_tree"] = base_tree
        tree = self._request("POST", f"/repos/{name}/git/trees",
                             json_body=tree_body) or {}
        tree_sha = tree.get("sha")
        if not tree_sha:
            raise GitHubError(502, "GitHub returned no tree sha")

        commit = self._request("POST", f"/repos/{name}/git/commits", json_body={
            "message": message, "tree": tree_sha,
            "parents": [parent_sha], "author": _builder_author()}) or {}
        commit_sha = commit.get("sha")
        if not commit_sha:
            raise GitHubError(502, "GitHub returned no commit sha")

        self._update_ref(name, ref, commit_sha)
        return commit_sha

    def delete_files(self, repo_full_name: str, branch: str,
                     paths: list[str], message: str) -> str:
        """Delete `paths` from the branch tip as ONE commit. Same
        blobs->tree->commit->ref walk as commit_files; a tree entry with
        `sha: null` removes that path from the new tree."""
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(branch)
        if not paths:
            raise GitHubError(400, "delete_files called with no paths")

        parent_sha = self.get_ref_sha(name, ref)
        if not parent_sha:
            raise GitHubError(404, f"branch does not exist: {ref}")
        parent = self._request(
            "GET", f"/repos/{name}/git/commits/{parent_sha}") or {}
        base_tree = (parent.get("tree") or {}).get("sha")
        if not base_tree:
            raise GitHubError(502, "GitHub returned no base tree sha")

        tree = self._request("POST", f"/repos/{name}/git/trees", json_body={
            "base_tree": base_tree,
            "tree": [{"path": safe_repo_path(p), "mode": _BLOB_MODE,
                      "type": "blob", "sha": None} for p in paths]}) or {}
        tree_sha = tree.get("sha")
        if not tree_sha:
            raise GitHubError(502, "GitHub returned no tree sha")
        commit = self._request("POST", f"/repos/{name}/git/commits", json_body={
            "message": message, "tree": tree_sha,
            "parents": [parent_sha], "author": _builder_author()}) or {}
        commit_sha = commit.get("sha")
        if not commit_sha:
            raise GitHubError(502, "GitHub returned no commit sha")
        self._update_ref(name, ref, commit_sha)
        return commit_sha

    def list_tree(self, repo_full_name: str, ref: str) -> list[dict]:
        """Every blob on `ref` as [{path, size, sha}] (recursive tree walk,
        one request). Used to enumerate a project's assets/ dir."""
        name = validate_repo_full_name(repo_full_name)
        ref = validate_branch(ref)
        sha = self.get_ref_sha(name, ref)
        if not sha:
            return []
        commit = self._request(
            "GET", f"/repos/{name}/git/commits/{sha}") or {}
        tree_sha = (commit.get("tree") or {}).get("sha")
        if not tree_sha:
            return []
        body = self._request(
            "GET", f"/repos/{name}/git/trees/{tree_sha}",
            params={"recursive": "1"}) or {}
        return [{"path": e.get("path"), "size": e.get("size"),
                 "sha": e.get("sha")}
                for e in (body.get("tree") or [])
                if e.get("type") == "blob" and e.get("path")]

    # ------------------------------------------------------------------ #
    # file read (used to re-read .appbuilder.json from the branch)

    def get_file_text(self, repo_full_name: str, ref: str,
                      path: str) -> str | None:
        name = validate_repo_full_name(repo_full_name)
        rel = safe_repo_path(path)
        body = self._request("GET", f"/repos/{name}/contents/{rel}",
                             params={"ref": ref}, allow_404=True)
        if not body or body.get("type") != "file":
            return None
        if body.get("encoding") == "base64":
            try:
                return base64.b64decode(body.get("content") or "").decode(
                    "utf-8", errors="replace")
            except (ValueError, TypeError):
                return None
        content = body.get("content")
        return content if isinstance(content, str) else None

    # ------------------------------------------------------------------ #
    # sync: compare / merge / commits

    def compare(self, repo_full_name: str, base: str, head: str) -> dict:
        """ahead/behind of `head` relative to `base`:
        {status, ahead_by, behind_by, commits:[...], files:[paths]}.
        `commits` are oldest-first as GitHub returns them."""
        name = validate_repo_full_name(repo_full_name)
        b, h = validate_branch(base), validate_branch(head)
        body = self._request("GET", f"/repos/{name}/compare/{b}...{h}",
                             params={"per_page": 100}) or {}
        return {
            "status": body.get("status") or "identical",
            "ahead_by": int(body.get("ahead_by") or 0),
            "behind_by": int(body.get("behind_by") or 0),
            "commits": [_norm_commit(c) for c in (body.get("commits") or [])],
            "files": [f.get("filename") for f in (body.get("files") or [])
                      if f.get("filename")],
        }

    def merge_branch(self, repo_full_name: str, into: str, source: str,
                     message: str) -> dict:
        """Merge `source` into `into` server-side (the merge API is atomic:
        it either creates the merge commit or changes nothing).
        Returns {merged: bool, sha}; merged=False means already up to date.
        Raises MergeConflictError on 409 with the paths changed on both
        sides."""
        name = validate_repo_full_name(repo_full_name)
        tgt, src = validate_branch(into), validate_branch(source)
        try:
            body = self._request("POST", f"/repos/{name}/merges", json_body={
                "base": tgt, "head": src, "commit_message": message})
        except GitHubError as e:
            if e.status == 409:
                raise MergeConflictError(
                    e.message, self._conflict_paths(name, tgt, src)) from None
            raise
        if not body:
            return {"merged": False, "sha": self.get_ref_sha(name, tgt)}
        return {"merged": True, "sha": body.get("sha")}

    def _conflict_paths(self, name: str, into: str, source: str) -> list[str]:
        """Files changed on BOTH sides since the merge base - the candidates
        GitHub refused to merge. Best effort: an error here must not mask the
        409 itself."""
        try:
            ours = set(self.compare(name, source, into)["files"])
            theirs = set(self.compare(name, into, source)["files"])
        except GitHubError:
            return []
        return sorted(ours & theirs)

    # ------------------------------------------------------------------ #
    # pull requests

    def enable_pages(self, repo_full_name: str) -> dict:
        """Switch the repo's GitHub Pages site on with build_type="workflow"
        (Actions deploys, no docs/ branch needed), so the scaffolded
        workflow's `pages` job has somewhere to publish the app preview.

        Deliberately soft-failing: a fine-grained PAT without Pages:write
        gets a 403, and an already-enabled site answers 409 — both return
        {"enabled": False/True-ish} rather than raising, because Pages is a
        preview nicety layered on top of the real artifact (the branch +
        PR), never a reason to fail a scaffold."""
        name = validate_repo_full_name(repo_full_name)
        try:
            self._request("POST", f"/repos/{name}/pages", json_body={
                "build_type": "workflow"})
            return {"enabled": True, "pages_url": pages_url(name)}
        except GitHubError as e:
            if e.status == 409:
                # Site already exists — the workflow deploy job works the
                # same either way, so this counts as enabled.
                return {"enabled": True, "pages_url": pages_url(name),
                        "already": True}
            if e.status in (401, 403, 404, 422):
                return {"enabled": False,
                        "reason": "the token cannot manage Pages on this "
                                  "repo (fine-grained PATs need Pages: "
                                  "read/write, or enable Pages manually "
                                  "with 'GitHub Actions' as the source)"}
            raise

    def create_pull(self, repo_full_name: str, *, head: str, base: str,
                    title: str, body: str) -> dict:
        name = validate_repo_full_name(repo_full_name)
        result = self._request("POST", f"/repos/{name}/pulls", json_body={
            "title": title, "body": body,
            "head": validate_branch(head), "base": validate_branch(base)}) or {}
        return {"pr_number": result.get("number"),
                "pr_url": result.get("html_url")}


# --------------------------------------------------------------------- #
# helpers

def safe_repo_path(path: str) -> str:
    """Normalize a project-relative path and refuse anything that could
    escape the repository root. Mirrors generate.py's existing
    escapes-project-dir guard, now that "the project" is a git tree rather
    than a directory on our disk."""
    rel = (path or "").replace("\\", "/").strip()
    if not rel:
        raise GitHubError(400, "empty file path")
    if rel.startswith("/") or rel.startswith("~"):
        raise GitHubError(400, f"absolute file path not allowed: {path!r}")
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise GitHubError(400, f"file path escapes the repository: {path!r}")
    if not parts:
        raise GitHubError(400, f"file path resolves to nothing: {path!r}")
    return "/".join(parts)


def _builder_author() -> dict:
    import time
    return {"name": BUILDER_AUTHOR_NAME, "email": BUILDER_AUTHOR_EMAIL,
            "date": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}


def _first_line(text) -> str:
    lines = str(text or "").splitlines()
    return lines[0] if lines else ""


def _norm_commit(c: dict) -> dict:
    commit = c.get("commit") or {}
    author = commit.get("author") or {}
    return {"sha": c.get("sha"),
            "message": _first_line(commit.get("message")),
            "author_name": author.get("name") or "",
            "author_email": author.get("email") or "",
            "author_login": (c.get("author") or {}).get("login") or "",
            "date": author.get("date") or "",
            "parents": len(c.get("parents") or [])}


def is_builder_commit(c: dict) -> bool:
    """True for a commit app-builder made itself (see BUILDER_* above).
    Commits made before the author stamp existed are recognised by the
    message shapes generate/assets/scaffold have always used (heuristic)."""
    msg = c.get("message") or ""
    return (c.get("author_email") == BUILDER_AUTHOR_EMAIL
            or c.get("author_name") == BUILDER_AUTHOR_NAME
            or msg.startswith(BUILDER_MESSAGE_PREFIX)
            or msg.startswith(_LEGACY_MESSAGE_PREFIXES))


_LEGACY_MESSAGE_PREFIXES = ("AI: ", "Scaffold from template ", "Asset: ",
                            "Remove asset: ")


def _b64(content: object) -> str:
    raw = content if isinstance(content, bytes) else str(content).encode("utf-8")
    return base64.b64encode(raw).decode("ascii")


def validate_repo_access(token: str, repo_full_name: str,
                         settings: Settings = SETTINGS) -> dict:
    """Prove the token can actually do the job BEFORE we store it, so a
    typo'd or under-scoped PAT is rejected at connect time instead of
    failing on the tenant's first real build.

    Returns {repo_full_name, default_branch, can_write}. Raises GitHubError
    with an actionable message on 401 (bad token), 404 (token cannot see this
    repo — for fine-grained tokens that usually means the repo was not
    selected under "Repository access"), or missing write permission.
    """
    name = validate_repo_full_name(repo_full_name)
    client = GitHubClient(token, settings)
    try:
        repo = client.get_repo(name)
    except GitHubError as e:
        if e.status == 401:
            raise GitHubError(401, "GitHub rejected this token. Check it was "
                                   "copied whole and has not expired.") from None
        if e.status in (403, 404):
            raise GitHubError(
                404, f"This token cannot see {name}. For a fine-grained "
                     "token, confirm the repository is selected under "
                     "'Repository access' and that it grants Contents: "
                     "read/write and Pull requests: read/write.") from None
        raise

    perms = repo.get("permissions")
    # `permissions` is present for any authenticated read; treat a missing
    # block as unknown rather than as failure (GitHub Enterprise Server has
    # historically omitted it) — the first push will surface a real 403 with
    # GitHub's own wording if write access truly is absent.
    if isinstance(perms, dict) and not perms.get("push"):
        raise GitHubError(
            403, f"This token can read {name} but not write to it. Grant "
                 "Contents: read/write (and Pull requests: read/write) and "
                 "reconnect.")

    return {
        "repo_full_name": repo.get("full_name") or name,
        "default_branch": repo.get("default_branch") or "main",
        "can_write": bool(perms.get("push")) if isinstance(perms, dict) else True,
    }
