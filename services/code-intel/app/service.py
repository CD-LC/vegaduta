"""Tenant-scoped orchestration over the vendored codebase_rag pipeline.

Every public method takes an authenticated TenantContext and never touches
anything outside that tenant's subtree. Index/graph state for a repo lives
in the tenant's repo dir (NOT inside the checkout), so git resets never
wipe the index and local-source repos never get a .coderag/ dropped into
the user's working tree.
"""

from __future__ import annotations

import contextlib
import threading
import time
from dataclasses import dataclass

from codebase_rag import graph as cr_graph
from codebase_rag.config import Config
from codebase_rag.embedders import make_embedder
from codebase_rag.pipeline import sync
from codebase_rag.store import Store

from . import gitops, registry
from .heavy_gate import HeavyGate
from .registry import RepoEntry
from .service_errors import (
    ApprovalNotAuthorizedError,
    ApprovalRequiredError,
    HeavyJobBusyError,
    PolicyDeniedError,
    RepoBusyError,
    RepoNotFoundError,
)
from .service_exec import GovernedExecutionMixin
from .settings import Settings
from .tenancy import TenantContext, repo_root, tenant_root

# The errors are re-exported: main.py, agent.py and the tests import them
# from here, next to the service that raises them.
__all__ = [
    "ApprovalNotAuthorizedError",
    "ApprovalRequiredError",
    "CodeIntelService",
    "HeavyJobBusyError",
    "PolicyDeniedError",
    "RepoBusyError",
    "RepoNotFoundError",
    "ServiceConfig",
]


class ServiceConfig(Config):
    """Config whose index/graph state lives OUTSIDE the checkout (in the
    tenant's repo dir), so git resets never wipe the index and local-source
    repos never get a .coderag/ dropped into the user's tree."""

    def __init__(self, state_dir_override: str, **kwargs):
        super().__init__(**kwargs)
        self._state_dir_override = state_dir_override

    @property
    def state_dir(self) -> str:  # type: ignore[override]
        return self._state_dir_override


@dataclass
class _RepoHandles:
    cfg: Config
    entry: RepoEntry


class CodeIntelService(GovernedExecutionMixin):
    def __init__(self, settings: Settings):
        self.settings = settings
        self._locks: dict[tuple[str, str], threading.Lock] = {}
        self._locks_guard = threading.Lock()
        self._heavy = HeavyGate(
            settings.max_heavy_jobs, settings.max_tenant_heavy_jobs,
            settings.max_queued_heavy_jobs,
            settings.heavy_retry_after_seconds)

    # ------------------------------------------------------------------ #

    def _lock_for(self, ctx: TenantContext, repo_id: str) -> threading.Lock:
        key = (ctx.tenant_id, repo_id)
        with self._locks_guard:
            if key not in self._locks:
                self._locks[key] = threading.Lock()
            return self._locks[key]

    @contextlib.contextmanager
    def _heavy_slot(self, ctx: TenantContext, wait: float = 0):
        self._heavy.acquire(ctx.tenant_id, wait)
        try:
            yield
        finally:
            self._heavy.release(ctx.tenant_id)

    def _handles(self, ctx: TenantContext, repo_id: str,
                 refresh_checkout: bool) -> _RepoHandles:
        entry = registry.get(self.settings.data_dir, ctx, repo_id)
        if entry is None:
            raise RepoNotFoundError(repo_id)
        rdir = repo_root(self.settings.data_dir, ctx, repo_id)
        # Local-source confinement root: the REQUESTING tenant's own subtree
        # (data/tenants/<tenant_id>/), not the shared data root - so a local
        # path can never point at another tenant's clones or SQLite indexes.
        troot = tenant_root(self.settings.data_dir, ctx)
        if refresh_checkout:
            worktree = gitops.ensure_checkout(
                entry, rdir, self.settings.git_timeout_seconds,
                data_root=troot)
        else:
            try:
                worktree = gitops.ensure_checkout(
                    entry, rdir, 5, data_root=troot)
            except Exception:
                # The fallback (reuse the last checkout) only makes sense for a
                # git repo whose quick fetch failed transiently. A local repo
                # that ensure_checkout rejected (missing, or escaping the data
                # root) must NOT fall back to entry.path - that would bypass
                # the H2 confinement and read the escaping path anyway.
                if entry.source_type == "local":
                    raise
                import os
                # entry.path is unset for git sources; the last-synced tree
                # in checkout/ is still searchable content, rdir itself is
                # just the state container - never index it directly.
                checkout_dir = os.path.join(rdir, "checkout")
                worktree = entry.path or (
                    checkout_dir if os.path.isdir(checkout_dir) else rdir)
        cfg = self._build_config(entry, worktree, rdir)
        return _RepoHandles(cfg=cfg, entry=entry)

    def _build_config(self, entry: RepoEntry, worktree: str,
                      repo_dir: str) -> Config:
        import os
        d_emb = self.settings.default_embedder
        d_model = self.settings.default_embed_model
        embedder = entry.embedder or d_emb
        embed_model = entry.embed_model or d_model
        allowed = self.settings.allowed_embed_models
        # Without an operator allow-list only the configured default is
        # usable; with one, the listed models too. Anything else is ignored
        # (keeps the shared embedder cache bounded against tenant input).
        is_default = embedder == d_emb and embed_model == d_model
        if not (is_default or (allowed and embed_model in allowed)):
            embedder, embed_model = d_emb, d_model
        cfg = ServiceConfig(
            state_dir_override=os.path.join(repo_dir, "state"),
            root=worktree,
            embedder=embedder,
            embed_model=embed_model,
        )
        # Load the repo's own .coderag.yml for chunking knobs, but strip
        # context_scripts unless the operator explicitly allowed them -
        # in a shared service they are arbitrary command execution.
        try:
            from codebase_rag.config import load_config
            repo_cfg = load_config(worktree)
            cfg.max_chunk_lines = repo_cfg.max_chunk_lines
            cfg.min_chunk_lines = repo_cfg.min_chunk_lines
            cfg.fallback_chunk_lines = repo_cfg.fallback_chunk_lines
            cfg.fallback_overlap_lines = repo_cfg.fallback_overlap_lines
            cfg.ignore = repo_cfg.ignore
            cfg.languages = repo_cfg.languages
            if self.settings.allow_context_scripts:
                cfg.context_scripts = repo_cfg.context_scripts
        except Exception:
            pass
        return cfg

    # ------------------------------------------------------------------ #
    # repos

    def register(self, ctx: TenantContext, entry: RepoEntry) -> RepoEntry:
        if entry.source_type == "git":
            rdir = repo_root(self.settings.data_dir, ctx, entry.repo_id)
            with self._heavy_slot(ctx):  # the clone is a heavy op
                gitops.ensure_checkout(entry, rdir,
                                       self.settings.git_timeout_seconds,
                                       data_root=tenant_root(
                                           self.settings.data_dir, ctx))
        elif entry.source_type == "local":
            import os
            if not entry.path or not os.path.isdir(entry.path):
                raise gitops.GitOpsValidationError(
                    f"local path does not exist: {entry.path}")
            # Confine caller-supplied local paths to the REQUESTING tenant's
            # own subtree (mirrors gitops.ensure_checkout's check) so
            # registration itself rejects path=/proc/self, /etc, /app AND
            # another tenant's clones/indexes under the shared data root.
            troot = os.path.realpath(tenant_root(self.settings.data_dir, ctx))
            resolved = os.path.realpath(entry.path)
            if resolved != troot and not resolved.startswith(troot + os.sep):
                raise gitops.GitOpsValidationError(
                    f"local path escapes the tenant's data subtree: {entry.path}")
        else:
            raise ValueError(f"unknown source_type: {entry.source_type}")
        return registry.upsert(self.settings.data_dir, ctx, entry)

    def list_repos(self, ctx: TenantContext) -> list[RepoEntry]:
        return list(registry.load(self.settings.data_dir, ctx).values())

    def delete_repo(self, ctx: TenantContext, repo_id: str) -> bool:
        import shutil
        ok = registry.delete(self.settings.data_dir, ctx, repo_id)
        if ok:
            rdir = repo_root(self.settings.data_dir, ctx, repo_id)
            shutil.rmtree(rdir, ignore_errors=True)
            # The clone's git metadata may live on container-local disk
            # (--separate-git-dir on chmod-less mounts); drop it too.
            gitops.discard_gitdir(rdir)
        return ok

    def delete_tenant(self, ctx: TenantContext) -> None:
        """Platform-admin "delete tenant completely" control (core side:
        CodeIntelClient#deleteTenantData) - removes this tenant's ENTIRE
        data/tenants/<tenant_id>/ tree: every repo clone, SQLite index/graph,
        and workspace, not just one repo at a time like delete_repo above.
        ignore_errors=True (same posture as delete_repo) since a tenant that
        never registered any repos has no directory to remove at all - that
        is success, not a failure.
        """
        import shutil
        # Container-local git dirs (--separate-git-dir on chmod-less mounts)
        # survive the tenant-subtree rmtree - drop them explicitly first.
        for rid in registry.load(self.settings.data_dir, ctx):
            gitops.discard_gitdir(
                repo_root(self.settings.data_dir, ctx, rid))
        shutil.rmtree(tenant_root(self.settings.data_dir, ctx),
                      ignore_errors=True)

    # ------------------------------------------------------------------ #
    # index / search

    def index(self, ctx: TenantContext, repo_id: str,
              full: bool = False, wait: float = 0) -> dict:
        """`wait` > 0 (background indexes) queues FIFO for a heavy-job slot
        that long instead of failing fast with HeavyJobBusyError."""
        # Slot first (queued FIFO when wait > 0), then the per-repo lock, so a
        # queued background job does not hold the repo lock (and 409 sync
        # callers) while it waits.
        try:
            self._heavy.acquire(ctx.tenant_id, wait)
        except HeavyJobBusyError as e:
            if wait > 0:  # background caller has no response to carry this
                entry = registry.get(self.settings.data_dir, ctx, repo_id)
                if entry:
                    # Separate field: never overwrite the last good last_index.
                    entry.last_capacity_error = {
                        "error": str(e)[:500], "at": time.time()}
                    registry.upsert(self.settings.data_dir, ctx, entry)
            raise
        lock = self._lock_for(ctx, repo_id)
        if not lock.acquire(blocking=False):
            self._heavy.release(ctx.tenant_id)
            raise RepoBusyError(f"repo '{repo_id}' is already being indexed")
        try:
            started = time.time()
            h = self._handles(ctx, repo_id, refresh_checkout=True)
            store = Store(h.cfg.db_path)
            try:
                embedder = make_embedder(h.cfg)
                stats = sync(h.cfg, store, embedder, full=full, quiet=True)
                # store.stats() after sync() - cumulative totals, not this
                # run's delta. Bug fix (2026-08-16, direct report): a clean
                # incremental re-index (no commits/dirty files since the
                # last run) legitimately reports every delta field as 0,
                # which reads exactly like a failed/empty index even though
                # the repo is fully indexed - files_scanned/chunks_embedded
                # above are ALWAYS per-run deltas, with no cumulative
                # counterpart to compare against (last_graph's "files" field
                # is cumulative, which is what made the two blocks look
                # inconsistent with each other). totals below close that gap
                # without changing what the delta fields mean.
                totals = store.stats()
                result = {
                    "status": "ok",
                    "stats": {
                        "files_scanned": stats.files_scanned,
                        "files_changed": stats.files_changed,
                        "files_deleted": stats.files_deleted,
                        "chunks_embedded": stats.chunks_embedded,
                        "chunks_reused": stats.chunks_reused,
                        "engines": stats.engines,
                        "files_total": totals["files"],
                        "chunks_total": totals["chunks"],
                    },
                    "started_at": started,
                    "finished_at": time.time(),
                }
            finally:
                store.close()
            h.entry.last_index = result
            h.entry.last_capacity_error = None
            registry.upsert(self.settings.data_dir, ctx, h.entry)
            return result
        except Exception as e:
            entry = registry.get(self.settings.data_dir, ctx, repo_id)
            if entry:
                entry.last_index = {"status": "error", "error": str(e)[:500],
                                    "started_at": started,
                                    "finished_at": time.time()}
                registry.upsert(self.settings.data_dir, ctx, entry)
            raise
        finally:
            self._heavy.release(ctx.tenant_id)
            lock.release()

    def search(self, ctx: TenantContext, repo_id: str, query: str,
               top_k: int = 8, path_prefix: str | None = None) -> list[dict]:
        h = self._handles(ctx, repo_id, refresh_checkout=False)
        store = Store(h.cfg.db_path)
        try:
            embedder = make_embedder(h.cfg)
            qvec = embedder.embed([query])[0]
            # Hybrid retrieval: cosine-embedding leg fused (via reciprocal
            # rank fusion) with a SQLite FTS5 lexical leg over symbol/
            # content/context, then reranked to boost symbol-name matches.
            # Falls back to pure cosine search transparently if FTS5 is
            # unavailable in the local SQLite build (see Store.__init__).
            hits = store.hybrid_search(qvec, query, top_k=top_k,
                                       path_prefix=path_prefix)
            return [{
                "path": x.path, "symbol": x.symbol,
                "start_line": x.start_line, "end_line": x.end_line,
                "score": x.score, "content": x.content, "context": x.context,
            } for x in hits]
        finally:
            store.close()

    # ------------------------------------------------------------------ #
    # knowledge-base bridge (read-only, grounded, cross-repo)

    def knowledge_query(self, ctx: TenantContext, query: str,
                        repo_id: str | None = None, top_k: int = 6) -> dict:
        """Grounded code retrieval for the knowledge experience. Every
        returned passage carries a citation (repo, path, line span); the
        response is retrieval only — no execution, no generation without a
        source — which keeps this endpoint zero-risk to expose broadly."""
        repos = ([repo_id] if repo_id
                 else [e.repo_id for e in self.list_repos(ctx)])
        passages: list[dict] = []
        for rid in repos:
            try:
                hits = self.search(ctx, rid, query, top_k=top_k)
            except Exception:
                continue
            for h in hits:
                passages.append({
                    "repo_id": rid,
                    "path": h["path"],
                    "symbol": h.get("symbol"),
                    "start_line": h["start_line"],
                    "end_line": h["end_line"],
                    "score": h["score"],
                    "citation": f"{rid}/{h['path']}:{h['start_line']}-{h['end_line']}",
                    "content": h["content"],
                })
        passages.sort(key=lambda p: p["score"], reverse=True)
        passages = passages[:top_k]
        return {
            "query": query,
            "grounded": bool(passages),   # no passages => the caller must not
                                          # fabricate an answer (RAG guardrail)
            "passages": passages,
            "citations": [p["citation"] for p in passages],
        }

    # ------------------------------------------------------------------ #
    # graph

    def graph_build(self, ctx: TenantContext, repo_id: str) -> dict:
        self._heavy.acquire(ctx.tenant_id)
        lock = self._lock_for(ctx, repo_id)
        if not lock.acquire(blocking=False):
            self._heavy.release(ctx.tenant_id)
            raise RepoBusyError(f"repo '{repo_id}' is busy")
        try:
            h = self._handles(ctx, repo_id, refresh_checkout=True)
            stats = cr_graph.build_graph(h.cfg, quiet=True)
            h.entry.last_graph = {"status": "ok", "stats": stats,
                                  "finished_at": time.time()}
            registry.upsert(self.settings.data_dir, ctx, h.entry)
            return stats
        finally:
            self._heavy.release(ctx.tenant_id)
            lock.release()

    def graph_callers(self, ctx: TenantContext, repo_id: str,
                      symbol: str) -> list[dict]:
        h = self._handles(ctx, repo_id, refresh_checkout=False)
        return cr_graph.callers_of(h.cfg, symbol)

    def graph_callees(self, ctx: TenantContext, repo_id: str,
                      symbol: str) -> list[dict]:
        h = self._handles(ctx, repo_id, refresh_checkout=False)
        return cr_graph.callees_of(h.cfg, symbol)

    def graph_impact(self, ctx: TenantContext, repo_id: str,
                     symbol: str, depth: int = 3) -> dict:
        h = self._handles(ctx, repo_id, refresh_checkout=False)
        return cr_graph.impact_of(h.cfg, symbol, depth=depth)

    def graph_stats(self, ctx: TenantContext, repo_id: str) -> dict:
        h = self._handles(ctx, repo_id, refresh_checkout=False)
        return cr_graph.graph_stats(h.cfg)

