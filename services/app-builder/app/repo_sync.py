"""Two-way sync between a project's app-builder branch and its base branch.

Push direction already exists (generate/assets commit to the branch). This
adds the pull direction and visibility:

* `status`           ahead/behind of the app-builder branch vs base
* `pull`             merge base INTO the branch via GitHub's merge API
* `detect_external`  commits on the branch that app-builder did not make

Half-state safety: GitHub's merge API is atomic - it either creates the merge
commit and advances the branch, or changes nothing (409 on conflict). We
update our own project record only AFTER a successful merge, so a conflict
leaves both GitHub and our state exactly as they were. Conflicted paths are
computed from the two compare diffs (files changed on both sides); GitHub does
not return them itself.
"""

from __future__ import annotations

from . import audit, github_credentials, projects
from .codeintel_client import client as ci_client
from .github_client import (GitHubClient, MergeConflictError,
                            BUILDER_MESSAGE_PREFIX, is_builder_commit)
from .settings import SETTINGS, Settings
from .tenancy import TenantContext, tenant_root


class SyncUnavailableError(RuntimeError):
    """Project has no GitHub branch (legacy local project)."""


class SyncConflictError(RuntimeError):
    def __init__(self, message: str, paths: list[str]):
        super().__init__(message)
        self.paths = paths


def _target(ctx: TenantContext, project_id: str, settings: Settings):
    project = projects.get_or_404(settings.data_dir, ctx, project_id)
    gh_state = project.github or {}
    repo, branch = gh_state.get("repo_full_name"), gh_state.get("branch")
    base = gh_state.get("base_branch") or "main"
    if not repo or not branch:
        raise SyncUnavailableError(
            "this project is not backed by a GitHub branch, so there is "
            "nothing to sync.")
    conn = github_credentials.require(ctx, settings)
    token = github_credentials.token_for(conn, settings)
    return project, repo, base, branch, GitHubClient(token, settings)


def summarize_status(cmp: dict, base: str, branch: str) -> dict:
    """Pure: shape a compare result for the UI."""
    ahead, behind = cmp["ahead_by"], cmp["behind_by"]
    return {"base_branch": base, "branch": branch, "status": cmp["status"],
            "ahead_by": ahead, "behind_by": behind,
            "up_to_date": behind == 0, "can_pull": behind > 0,
            "diverged": ahead > 0 and behind > 0}


def status(ctx: TenantContext, project_id: str,
           settings: Settings = SETTINGS) -> dict:
    _p, repo, base, branch, gh = _target(ctx, project_id, settings)
    return summarize_status(gh.compare(repo, base, branch), base, branch)


def external_commits(commits: list[dict]) -> list[dict]:
    """Pure: the commits app-builder did not make, newest first."""
    return [{"sha": c["sha"], "message": c["message"],
             "author": c["author_name"] or c["author_login"],
             "date": c["date"]}
            for c in reversed(commits) if not is_builder_commit(c)]


def detect_external(ctx: TenantContext, project_id: str,
                    settings: Settings = SETTINGS) -> dict:
    _p, repo, base, branch, gh = _target(ctx, project_id, settings)
    cmp = gh.compare(repo, base, branch)
    ext = external_commits(cmp["commits"])
    return {"branch": branch, "edited_outside": bool(ext),
            "count": len(ext), "commits": ext,
            "truncated": cmp["ahead_by"] > len(cmp["commits"])}


def pull(ctx: TenantContext, project_id: str,
         settings: Settings = SETTINGS) -> dict:
    project, repo, base, branch, gh = _target(ctx, project_id, settings)
    cmp = gh.compare(repo, base, branch)
    if cmp["behind_by"] == 0:
        return {"merged": False, "up_to_date": True, "sha": None,
                "reindexed": False}
    try:
        res = gh.merge_branch(
            repo, into=branch, source=base,
            message=f"{BUILDER_MESSAGE_PREFIX} merge {base} into {branch}")
    except MergeConflictError as e:
        audit.record(tenant_root(settings.data_dir, ctx), {
            "kind": "sync_pull_conflict", "project_id": project_id,
            "actor": ctx.subject, "paths": e.paths[:50]})
        raise SyncConflictError(
            f"{base} cannot be merged into {branch} cleanly. Resolve the "
            "conflicts on GitHub (or rebase the branch); nothing was changed.",
            e.paths) from None

    fresh = projects.get_or_404(settings.data_dir, ctx, project_id)
    gh_state = dict(fresh.github or {})
    if res.get("sha"):
        gh_state["commit_sha"] = res["sha"]
    fresh.github = gh_state
    projects.upsert(settings.data_dir, ctx, fresh)

    reindexed = True
    try:
        ci_client.index_repo(ctx, project_id)
    except Exception:
        # The merge itself succeeded; a stale index is recoverable and must
        # not turn a real merge into an error the user retries.
        reindexed = False
    audit.record(tenant_root(settings.data_dir, ctx), {
        "kind": "sync_pull", "project_id": project_id, "actor": ctx.subject,
        "repo_full_name": repo, "base_branch": base, "branch": branch,
        "sha": res.get("sha"), "merged": res.get("merged"),
        "reindexed": reindexed})
    return {"merged": bool(res.get("merged")), "up_to_date": False,
            "sha": res.get("sha"), "reindexed": reindexed}
