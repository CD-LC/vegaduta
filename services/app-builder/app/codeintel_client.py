"""Thin HTTP client for code-intel's REST API.

app-builder is a CLIENT of code-intel — it never vendors or duplicates
code-intel's indexing/execution engine. Every call here authenticates as a
trusted server-to-server caller using code-intel's own `internal` auth
mode (X-Tenant-Id + X-Internal-Token — see code-intel/app/auth.py's
_internal_auth), regardless of how the caller authenticated to
app-builder itself, exactly the same trust pattern core uses when it calls
code-intel today.

Endpoints used as-is (already live in code-intel/app/main.py, read there
for the authoritative request/response shapes):
  POST   /repos                                      register
  POST   /repos/{repo_id}/index                       index
  POST   /repos/{repo_id}/search                      search
  POST   /repos/{repo_id}/workspaces                   create workspace
  POST   /repos/{repo_id}/workspaces/{ws}/apply        write files
  POST   /repos/{repo_id}/workspaces/{ws}/run          one-shot command
  GET    /repos/{repo_id}/workspaces/{ws}/diff         unified diff
  DELETE /repos/{repo_id}/workspaces/{ws}              delete workspace
  DELETE /repos/{repo_id}                              delete repo

Endpoints assumed per the parallel code-intel workstream's contract (NOT
yet landed as of this writing — see app-builder/README.md's "Known
limitations" section). These mirror the existing one-shot `/run` route
1:1 (same approval_id query param / 428 / guardrail-policy pattern), just
persistent instead of one-shot:
  POST   /repos/{repo_id}/workspaces/{ws}/processes            start
  GET    /repos/{repo_id}/workspaces/{ws}/processes/{pid}      status
  DELETE /repos/{repo_id}/workspaces/{ws}/processes/{pid}      stop

If the landed shape differs from what's assumed here, the fix is
contained entirely to `create_process`/`get_process`/`delete_process`
below plus preview.py's callers — nothing else in this service touches
process response fields directly.
"""

from __future__ import annotations

import httpx

from .settings import SETTINGS, Settings
from .tenancy import TenantContext


class CodeIntelClientError(RuntimeError):
    """Raised for any non-2xx response from code-intel, or a transport
    failure (timeout, connection refused). `status` mirrors the HTTP
    status where one exists (0 for pure transport failures) so callers
    (main.py's exception handler) can propagate something meaningful
    rather than a blanket 500."""

    def __init__(self, status: int, message: str, body: dict | None = None):
        super().__init__(message)
        self.status = status
        self.message = message
        self.body = body or {}


class CodeIntelClient:
    def __init__(self, settings: Settings):
        self.settings = settings

    def _headers(self, ctx: TenantContext) -> dict[str, str]:
        return {
            "X-Tenant-Id": ctx.tenant_id,
            "X-Internal-Token": self.settings.code_intel_internal_token,
            "Content-Type": "application/json",
        }

    def _request(self, method: str, ctx: TenantContext, path: str,
                 json_body: dict | None = None,
                 params: dict | None = None) -> dict:
        url = self.settings.code_intel_url.rstrip("/") + path
        try:
            resp = httpx.request(
                method, url, headers=self._headers(ctx), json=json_body,
                params=params, timeout=self.settings.code_intel_timeout_seconds)
        except httpx.TimeoutException as e:
            raise CodeIntelClientError(
                504, f"code-intel request timed out: {path}") from e
        except httpx.TransportError as e:
            raise CodeIntelClientError(
                0, f"code-intel unreachable at {url}: {e}") from e

        if resp.status_code >= 400:
            try:
                body = resp.json()
            except ValueError:
                body = {"error": resp.text[:500]}
            raise CodeIntelClientError(
                resp.status_code,
                body.get("error", f"code-intel returned {resp.status_code}"),
                body)
        if not resp.content:
            return {}
        return resp.json()

    # ------------------------------------------------------------------ #
    # repos

    def register_repo(self, ctx: TenantContext, repo_id: str, *,
                      source_type: str = "local", path: str | None = None,
                      git_url: str | None = None, git_ref: str | None = None,
                      auth_env: str | None = None,
                      auth_token: str | None = None,
                      embedder: str | None = None,
                      embed_model: str | None = None) -> dict:
        """Register (or re-register — POST /repos upserts) a repo.

        `auth_token` is the bring-your-own-repo path
        (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §2): a tenant's
        decrypted GitHub PAT passed directly, because `auth_env` is an
        environment-variable NAME and you cannot set one env var per tenant in
        a running container. code-intel feeds it through the exact same
        ephemeral-credential-helper mechanism `gitops.py` already has, and
        takes it in preference to `auth_env` when both are present.

        code-intel deliberately does NOT persist `auth_token` to its
        registry.json — it lives in that process's in-memory RepoEntry only.
        So callers must re-register before any operation that needs a fresh
        clone/fetch (see generate.py's `_promote_to_branch`), which is what
        keeps this working across a code-intel restart.

        The token is passed straight through in the request body and never
        held, logged, or echoed by this client: the caller decrypts it just in
        time (`github_credentials.token_for()`) and drops it when the request
        ends.
        """
        return self._request("POST", ctx, "/repos", json_body={
            "repo_id": repo_id, "source_type": source_type, "path": path,
            "git_url": git_url, "git_ref": git_ref, "auth_env": auth_env,
            "auth_token": auth_token,
            "embedder": embedder, "embed_model": embed_model})

    def index_repo(self, ctx: TenantContext, repo_id: str, *,
                   full: bool = False, background: bool = False) -> dict:
        return self._request(
            "POST", ctx, f"/repos/{repo_id}/index",
            json_body={"full": full, "background": background})

    def delete_repo(self, ctx: TenantContext, repo_id: str) -> dict:
        return self._request("DELETE", ctx, f"/repos/{repo_id}")

    def search(self, ctx: TenantContext, repo_id: str, query: str, *,
              top_k: int = 8, path_prefix: str | None = None) -> list[dict]:
        result = self._request(
            "POST", ctx, f"/repos/{repo_id}/search",
            json_body={"query": query, "top_k": top_k,
                      "path_prefix": path_prefix})
        return result if isinstance(result, list) else []

    # ------------------------------------------------------------------ #
    # execution workspaces (rework loop) — 403 if code-intel has exec off

    def create_workspace(self, ctx: TenantContext, repo_id: str) -> dict:
        return self._request("POST", ctx, f"/repos/{repo_id}/workspaces")

    def apply_files(self, ctx: TenantContext, repo_id: str, ws_id: str,
                    files: list[dict], *,
                    approval_id: str | None = None) -> dict:
        params = {"approval_id": approval_id} if approval_id else None
        return self._request(
            "POST", ctx, f"/repos/{repo_id}/workspaces/{ws_id}/apply",
            json_body={"files": files}, params=params)

    def run(self, ctx: TenantContext, repo_id: str, ws_id: str,
           command: str, *, timeout: int | None = None, attempts: int = 1,
           approval_id: str | None = None) -> dict:
        params = {"approval_id": approval_id} if approval_id else None
        return self._request(
            "POST", ctx, f"/repos/{repo_id}/workspaces/{ws_id}/run",
            json_body={"command": command, "timeout": timeout,
                      "attempts": attempts}, params=params)

    def diff(self, ctx: TenantContext, repo_id: str, ws_id: str) -> dict:
        return self._request(
            "GET", ctx, f"/repos/{repo_id}/workspaces/{ws_id}/diff")

    def delete_workspace(self, ctx: TenantContext, repo_id: str,
                         ws_id: str) -> dict:
        return self._request(
            "DELETE", ctx, f"/repos/{repo_id}/workspaces/{ws_id}")

    # ------------------------------------------------------------------ #
    # long-running processes (preview servers) — see module docstring for
    # the assumed contract; landing in a parallel code-intel workstream.

    def create_process(self, ctx: TenantContext, repo_id: str, ws_id: str,
                       command: str, *, run_mode: str | None = None,
                       image: str | None = None, port: int | None = None,
                       env_overrides: dict | None = None) -> dict:
        return self._request(
            "POST", ctx, f"/repos/{repo_id}/workspaces/{ws_id}/processes",
            json_body={"command": command, "run_mode": run_mode,
                      "image": image, "port": port,
                      "env_overrides": env_overrides})

    def get_process(self, ctx: TenantContext, repo_id: str, ws_id: str,
                    process_id: str) -> dict:
        return self._request(
            "GET", ctx,
            f"/repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}")

    def delete_process(self, ctx: TenantContext, repo_id: str, ws_id: str,
                       process_id: str) -> dict:
        return self._request(
            "DELETE", ctx,
            f"/repos/{repo_id}/workspaces/{ws_id}/processes/{process_id}")


client = CodeIntelClient(SETTINGS)
