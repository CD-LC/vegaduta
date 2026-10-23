"""Heavy-job admission, embedder LRU, repo size limits.

Plain pytest `test_*` functions (also runnable without pytest:
`python3 -m tests.test_heavy_caps` from code-intel/). If numpy is not
installed (bare dev box) a tiny stub is registered so the embedder-cache
tests, which use the numpy-free fake embedders, can still import."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

try:
    import numpy  # noqa: F401
except ImportError:  # pragma: no cover - only on boxes without numpy
    import types

    _np = types.ModuleType("numpy")
    _np.ndarray = object
    _np.float32 = float
    sys.modules["numpy"] = _np

os.environ["CODE_INTEL_ENFORCE_REPO_LIMITS"] = "true"  # limits are opt-in

from app import gitops
from app.heavy_gate import HeavyGate as _HeavyGate
from app.service import HeavyJobBusyError
from codebase_rag import embedders


def _raises(fn, exc) -> bool:
    try:
        fn()
    except exc:
        return True
    return False


def _wait_for(pred, secs=3.0) -> bool:
    end = time.monotonic() + secs
    while time.monotonic() < end:
        if pred():
            return True
        time.sleep(0.01)
    return False


# --------------------------------------------------------------- gate


def test_gate_caps_and_retry_after():
    g = _HeavyGate(max_jobs=2, max_per_tenant=1, max_queued=1, retry_after=7)
    g.acquire("a")
    assert _raises(lambda: g.acquire("a"), HeavyJobBusyError)
    g.acquire("b")
    try:
        g.acquire("c")
        raise AssertionError("global cap should reject")
    except HeavyJobBusyError as e:
        assert e.retry_after == 7
    g.release("a")
    g.release("b")
    assert g._running == 0 and not g._by_tenant


def test_gate_per_tenant_default_allows_two():
    g = _HeavyGate(2, 2, 1, 1)
    g.acquire("a")
    g.acquire("a")
    assert _raises(lambda: g.acquire("a"), HeavyJobBusyError)


def test_gate_waiters_are_fifo_and_sync_cannot_barge():
    g = _HeavyGate(max_jobs=1, max_per_tenant=1, max_queued=4, retry_after=1)
    g.acquire("x")
    order: list[str] = []

    def waiter(name, tenant):
        g.acquire(tenant, wait=5)
        order.append(name)
        time.sleep(0.05)
        g.release(tenant)

    t1 = threading.Thread(target=waiter, args=("first", "t1"))
    t1.start()
    assert _wait_for(lambda: g._queued == 1)
    t2 = threading.Thread(target=waiter, args=("second", "t2"))
    t2.start()
    assert _wait_for(lambda: g._queued == 2)
    g.release("x")
    t1.join(5)
    t2.join(5)
    assert order == ["first", "second"]
    assert g._running == 0 and not g._queue and not g._by_tenant


def test_gate_new_arrival_blocked_by_admissible_waiter():
    g = _HeavyGate(1, 1, 4, 1)
    tok = object()
    g._queue.append((tok, "t1"))  # a waiter that could run right now
    assert g._blocked_by_earlier(None)
    assert _raises(lambda: g.acquire("t3"), HeavyJobBusyError)
    assert not g._blocked_by_earlier(tok)  # the waiter itself is first


def test_gate_queue_limit_and_timeout():
    g = _HeavyGate(1, 1, 1, 1)
    g.acquire("a")
    t = threading.Thread(target=lambda: _raises(lambda: g.acquire("b", wait=1), HeavyJobBusyError))
    t.start()
    assert _wait_for(lambda: g._queued == 1)
    assert _raises(lambda: g.acquire("c", wait=1), HeavyJobBusyError)
    t.join(5)
    assert g._queued == 0  # timed-out waiter cleaned up
    assert _raises(lambda: g.acquire("d", wait=0.1), HeavyJobBusyError)


# ---------------------------------------------------- embedder cache


class _Cfg:
    def __init__(self, model):
        self.embedder = "fake"
        self.embed_model = model
        self.openai_base_url = None


def test_embedder_cache_is_bounded_lru_and_serialized():
    built: list[str] = []

    class Fake(embedders.Embedder):
        name = "fake"

        def __init__(self, model):
            self.model = model
            built.append(model)
            self.active = 0
            self.max_active = 0

        def embed(self, texts):
            self.active += 1
            self.max_active = max(self.max_active, self.active)
            time.sleep(0.01)
            self.active -= 1
            return texts

    orig_build = embedders._build_embedder
    embedders._build_embedder = lambda cfg: Fake(cfg.embed_model)
    os.environ["CODE_INTEL_EMBEDDER_CACHE"] = "2"
    embedders._EMBEDDER_CACHE.clear()
    try:
        a = embedders.make_embedder(_Cfg("a"))
        assert embedders.make_embedder(_Cfg("a")) is a
        embedders.make_embedder(_Cfg("b"))
        embedders.make_embedder(_Cfg("a"))  # a is now most recent
        embedders.make_embedder(_Cfg("c"))  # evicts b
        assert len(embedders._EMBEDDER_CACHE) == 2
        keys = [k[1] for k in embedders._EMBEDDER_CACHE]
        assert keys == ["a", "c"]
        embedders.make_embedder(_Cfg("b"))  # rebuilt
        assert built.count("b") == 2
        # concurrent encode on one shared instance is serialized
        inner = a._inner
        ts = [threading.Thread(target=lambda: a.embed(["x"])) for _ in range(6)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        assert inner.max_active == 1
        assert a.identity == "fake:a"
    finally:
        embedders._build_embedder = orig_build
        os.environ.pop("CODE_INTEL_EMBEDDER_CACHE", None)
        embedders._EMBEDDER_CACHE.clear()


# ------------------------------------------------------ size limits


def _git(cwd, *args):
    subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t", *args],
        cwd=cwd,
        check=True,
        capture_output=True,
    )


def test_tenant_quota_off_by_default_and_limits_ignore_dot_git():
    os.environ.pop("CODE_INTEL_TENANT_QUOTA_MB", None)
    assert gitops._limits()[2] == 0
    tmp = tempfile.mkdtemp(prefix="codeintel-caps-")
    old = {k: os.environ.get(k) for k in ("CODE_INTEL_MAX_REPO_FILES", "CODE_INTEL_MAX_REPO_MB")}
    try:
        os.makedirs(os.path.join(tmp, ".git"))
        with open(os.path.join(tmp, ".git", "big"), "wb") as f:
            f.write(b"x" * 100_000)
        for i in range(5):
            with open(os.path.join(tmp, f"f{i}"), "w") as f:
                f.write("hello")
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "10"
        os.environ["CODE_INTEL_MAX_REPO_MB"] = "1"
        gitops._check_repo_size(tmp)
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "3"
        assert _raises(lambda: gitops._check_repo_size(tmp), gitops.RepoTooLargeError)
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "0"
        os.environ["CODE_INTEL_MAX_REPO_MB"] = "0"
        gitops._check_repo_size(tmp)
        gitops._check_tenant_quota(tmp)  # quota 0 -> no-op
    finally:
        for k, v in old.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(tmp, ignore_errors=True)


def test_update_checkout_skips_walk_when_unchanged_and_rolls_back_oversize():
    tmp = tempfile.mkdtemp(prefix="codeintel-upd-")
    saved = os.environ.get("CODE_INTEL_MAX_REPO_FILES")
    try:
        origin = os.path.join(tmp, "origin")
        co = os.path.join(tmp, "co")
        os.makedirs(origin)
        _git(origin, "init", "-q", "-b", "main")
        with open(os.path.join(origin, "a.txt"), "w") as f:
            f.write("a")
        _git(origin, "add", ".")
        _git(origin, "commit", "-qm", "1")
        subprocess.run(["git", "clone", "-q", origin, co], check=True, capture_output=True)
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "3"
        calls = []
        orig = gitops._check_repo_size
        gitops._check_repo_size = lambda p: (calls.append(p), orig(p))[1]
        try:
            _git(co, "fetch", "-q", "origin")
            gitops._update_checkout(co, "origin/main", 30)
            assert calls == []  # unchanged tree -> no walk at all
            # upstream grows past the file limit
            for i in range(5):
                with open(os.path.join(origin, f"n{i}.txt"), "w") as f:
                    f.write("n")
            _git(origin, "add", ".")
            _git(origin, "commit", "-qm", "2")
            _git(co, "fetch", "-q", "origin")
            assert _raises(
                lambda: gitops._update_checkout(co, "origin/main", 30), gitops.RepoTooLargeError
            )
            # rejected before reset --hard: checkout still the old tree
            assert sorted(f for f in os.listdir(co) if f != ".git") == ["a.txt"]
            assert calls == []  # file-count refusal needed no tree walk
        finally:
            gitops._check_repo_size = orig
    finally:
        if saved is None:
            os.environ.pop("CODE_INTEL_MAX_REPO_FILES", None)
        else:
            os.environ["CODE_INTEL_MAX_REPO_FILES"] = saved
        shutil.rmtree(tmp, ignore_errors=True)


def test_update_checkout_byte_limit_resets_back():
    tmp = tempfile.mkdtemp(prefix="codeintel-upd2-")
    saved = {k: os.environ.get(k) for k in ("CODE_INTEL_MAX_REPO_FILES", "CODE_INTEL_MAX_REPO_MB")}
    try:
        origin = os.path.join(tmp, "origin")
        co = os.path.join(tmp, "co")
        os.makedirs(origin)
        _git(origin, "init", "-q", "-b", "main")
        with open(os.path.join(origin, "a.txt"), "w") as f:
            f.write("a")
        _git(origin, "add", ".")
        _git(origin, "commit", "-qm", "1")
        subprocess.run(["git", "clone", "-q", origin, co], check=True, capture_output=True)
        os.environ["CODE_INTEL_MAX_REPO_MB"] = "1"
        os.environ["CODE_INTEL_MAX_REPO_FILES"] = "0"
        with open(os.path.join(origin, "big.bin"), "wb") as f:
            f.write(os.urandom(2 * 1024 * 1024))
        _git(origin, "add", ".")
        _git(origin, "commit", "-qm", "2")
        _git(co, "fetch", "-q", "origin")
        assert _raises(
            lambda: gitops._update_checkout(co, "origin/main", 30), gitops.RepoTooLargeError
        )
        assert not os.path.exists(os.path.join(co, "big.bin"))
    finally:
        for k, v in saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    fails = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print("  ok   ", name)
            except Exception as e:
                fails += 1
                print("  FAIL ", name, repr(e))
    raise SystemExit(1 if fails else 0)
