"""Rolling-deploy registry compat, rejected-sha memo, default-off limits,
embedder allow-list, and service-level slot/handler behaviour (stubbed
git/embedder). Run: python3 -m tests.test_round5 (from code-intel/), or pytest.
"""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    import numpy  # noqa: F401
except ImportError:  # pragma: no cover
    import types

    _np = types.ModuleType("numpy")
    _np.ndarray = object
    _np.float32 = float
    sys.modules["numpy"] = _np

from app import gitops, registry
from app import service as svc
from app.registry import RepoEntry
from app.settings import Settings
from app.tenancy import TenantContext

CTX = (
    TenantContext(tenant_id="t1")
    if "tenant_id" in getattr(TenantContext, "__dataclass_fields__", {})
    else None
)


def _ctx():
    return CTX or TenantContext("t1")


def _raises(fn, exc):
    try:
        fn()
    except exc:
        return True
    return False


# ------------------------------------------------------------ registry


@dataclass
class _OldRepoEntry:  # the pre-last_capacity_error schema (old image)
    repo_id: str
    source_type: str
    path: str | None = None
    git_url: str | None = None
    git_ref: str | None = None
    auth_env: str | None = None
    auth_token: str | None = None
    embedder: str | None = None
    embed_model: str | None = None
    created_at: float = 0.0
    last_index: dict | None = None
    last_graph: dict | None = None


def test_registry_file_readable_by_old_schema_loader():
    d = tempfile.mkdtemp()
    try:
        ctx = _ctx()
        registry.upsert(d, ctx, RepoEntry(repo_id="r1", source_type="local", path="/x"))
        path = registry._registry_path(d, ctx)
        raw = json.load(open(path))
        assert "last_capacity_error" not in raw["r1"]
        # exactly what an old image does:
        old = {k: _OldRepoEntry(**v) for k, v in raw.items()}
        assert old["r1"].repo_id == "r1"
    finally:
        shutil.rmtree(d, ignore_errors=True)


def test_registry_load_ignores_unknown_keys_and_keeps_capacity_error():
    d = tempfile.mkdtemp()
    try:
        ctx = _ctx()
        e = RepoEntry(repo_id="r1", source_type="local", path="/x")
        e.last_capacity_error = {"error": "busy", "at": 1.0}
        registry.upsert(d, ctx, e)
        path = registry._registry_path(d, ctx)
        raw = json.load(open(path))
        raw["r1"]["field_from_the_future"] = 1
        json.dump(raw, open(path, "w"))
        got = registry.get(d, ctx, "r1")
        assert got.last_capacity_error == {"error": "busy", "at": 1.0}
    finally:
        shutil.rmtree(d, ignore_errors=True)


# -------------------------------------------------------------- gitops


def _git(cwd, *a):
    subprocess.run(
        ["git", *a],
        cwd=cwd,
        check=True,
        capture_output=True,
        env={
            **os.environ,
            "GIT_AUTHOR_NAME": "t",
            "GIT_AUTHOR_EMAIL": "t@t",
            "GIT_COMMITTER_NAME": "t",
            "GIT_COMMITTER_EMAIL": "t@t",
        },
    )


def test_limits_default_off_and_opt_in():
    saved = {
        k: os.environ.pop(k, None)
        for k in ("CODE_INTEL_ENFORCE_REPO_LIMITS", "CODE_INTEL_MAX_REPO_FILES")
    }
    try:
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "1"
        assert gitops._limits()[:2] == (0, 0)
        os.environ["CODE_INTEL_ENFORCE_REPO_LIMITS"] = "true"
        assert gitops._limits()[1] == 1
    finally:
        for k, v in saved.items():
            os.environ.pop(k, None)
            if v is not None:
                os.environ[k] = v


def test_rejected_commit_is_not_reset_again():
    saved = {
        k: os.environ.get(k)
        for k in ("CODE_INTEL_ENFORCE_REPO_LIMITS", "CODE_INTEL_MAX_REPO_FILES")
    }
    d = tempfile.mkdtemp()
    try:
        os.environ["CODE_INTEL_ENFORCE_REPO_LIMITS"] = "true"
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "2"
        _git(d, "init", "-q", "-b", "main")
        open(os.path.join(d, "a"), "w").write("1")
        _git(d, "add", ".")
        _git(d, "commit", "-qm", "c1")
        old = gitops._rev(d, "HEAD", 10)
        for n in "bcd":
            open(os.path.join(d, n), "w").write("1")
        _git(d, "add", ".")
        _git(d, "commit", "-qm", "c2")
        new = gitops._rev(d, "HEAD", 10)
        _git(d, "branch", "target", new)
        _git(d, "reset", "-q", "--hard", old)
        assert _raises(lambda: gitops._update_checkout(d, "target", 10), gitops.RepoTooLargeError)
        assert gitops._rev(d, "HEAD", 10) == old
        # second call must raise from the memo, without touching the tree
        calls = []
        orig = gitops._run
        gitops._run = lambda *a, **k: calls.append(a[0]) or orig(*a, **k)
        try:
            assert _raises(
                lambda: gitops._update_checkout(d, "target", 10), gitops.RepoTooLargeError
            )
        finally:
            gitops._run = orig
        assert not any(c[0] == "reset" for c in calls), calls
        assert gitops._rev(d, "HEAD", 10) == old
    finally:
        for k, v in saved.items():
            os.environ.pop(k, None)
            if v is not None:
                os.environ[k] = v
        gitops._REJECTED.clear()
        shutil.rmtree(d, ignore_errors=True)


# ------------------------------------------------------------- service


def _service(tmp, **kw):
    return svc.CodeIntelService(Settings(data_dir=tmp, **kw))


def test_defaults_per_tenant_below_global():
    s = Settings()
    assert s.max_heavy_jobs == 4 and s.max_tenant_heavy_jobs == 2
    assert s.max_tenant_heavy_jobs < s.max_heavy_jobs


def test_slot_released_on_repo_busy_error():
    d = tempfile.mkdtemp()
    try:
        s = _service(d)
        ctx = _ctx()
        lock = s._lock_for(ctx, "r1")
        lock.acquire()
        try:
            assert _raises(lambda: s.index(ctx, "r1"), svc.RepoBusyError)
        finally:
            lock.release()
        assert s._heavy._running == 0 and not s._heavy._by_tenant
    finally:
        shutil.rmtree(d, ignore_errors=True)


def test_slot_released_when_index_fails_and_capacity_error_recorded():
    d = tempfile.mkdtemp()
    try:
        s = _service(d, max_heavy_jobs=1, max_tenant_heavy_jobs=1)
        ctx = _ctx()
        registry.upsert(d, ctx, RepoEntry(repo_id="r1", source_type="local", path="/x"))
        s._heavy.acquire("other")  # global cap taken by someone else
        assert _raises(lambda: s.index(ctx, "r1"), svc.HeavyJobBusyError)
        # background caller (wait>0) records it without touching last_index
        s2 = _service(d, max_heavy_jobs=1, max_tenant_heavy_jobs=1, heavy_queue_wait_seconds=1)
        s2._heavy.acquire("other")
        assert _raises(lambda: s2.index(ctx, "r1", wait=0.05), svc.HeavyJobBusyError)
        e = registry.get(d, ctx, "r1")
        assert e.last_capacity_error and e.last_index is None
        s._heavy.release("other")
        s2._heavy.release("other")
        # a stubbed checkout failure still releases the slot
        orig = gitops.ensure_checkout
        gitops.ensure_checkout = lambda *a, **k: (_ for _ in ()).throw(gitops.GitOpsError("boom"))
        try:
            e.source_type = "git"
            e.git_url = "https://example.com/x.git"
            registry.upsert(d, ctx, e)
            assert _raises(lambda: s.index(ctx, "r1"), gitops.GitOpsError)
        finally:
            gitops.ensure_checkout = orig
        assert s._heavy._running == 0 and not s._heavy._by_tenant
    finally:
        shutil.rmtree(d, ignore_errors=True)


def test_http_handlers_map_429_413_409():
    try:
        from app import main
    except ImportError:
        print("  skip  test_http_handlers_map_429_413_409 (fastapi missing)")
        return
    h = dict(main.app.exception_handlers.items())
    r = asyncio.run(h[svc.HeavyJobBusyError](None, svc.HeavyJobBusyError("busy", retry_after=9)))
    assert r.status_code == 429 and r.headers["Retry-After"] == "9"
    r = asyncio.run(h[gitops.RepoTooLargeError](None, gitops.RepoTooLargeError("x")))
    assert r.status_code == 413
    r = asyncio.run(h[svc.RepoBusyError](None, svc.RepoBusyError("x")))
    assert r.status_code == 409


def _cfg(s, **entry_kw):
    e = RepoEntry(repo_id="r", source_type="local", path="/x", **entry_kw)
    return s._build_config(e, "/nonexistent", "/tmp/r5-state")


def test_embedder_allow_list_fallback():
    d = tempfile.mkdtemp()
    try:
        # no allow-list: tenant model ignored, default used
        s = _service(d, default_embedder="hash", default_embed_model="m0")
        c = _cfg(s, embedder="fastembed", embed_model="huge")
        assert (c.embedder, c.embed_model) == ("hash", "m0")
        c = _cfg(s)
        assert (c.embedder, c.embed_model) == ("hash", "m0")
        # allow-list: listed model honoured, unlisted ignored
        s = _service(
            d,
            default_embedder="hash",
            default_embed_model="m0",
            allowed_embed_models=frozenset({"ok"}),
        )
        c = _cfg(s, embedder="fastembed", embed_model="ok")
        assert (c.embedder, c.embed_model) == ("fastembed", "ok")
        c = _cfg(s, embedder="fastembed", embed_model="huge")
        assert (c.embedder, c.embed_model) == ("hash", "m0")
    finally:
        shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    bad = 0
    for n, f in sorted(globals().items()):
        if n.startswith("test_") and callable(f):
            try:
                f()
                print("  ok   ", n)
            except Exception as ex:
                import traceback

                traceback.print_exc()
                bad += 1
                print("  FAIL ", n, repr(ex))
    sys.exit(1 if bad else 0)
