"""SEC-003: per-execution uids, directory modes and kill-by-uid.

Everything OS-level is mocked (a fake /proc tree, recorded chown/chmod/kill),
so these run on any host - including Windows, which has no /proc, no geteuid
and no chown. The real-kernel behaviour is covered by running the service
image; see the tool-sandbox log for what was and was not verified live.
"""

from __future__ import annotations

import logging
import os
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import isolation, proc, runner
from app.settings import Settings, parse_uid_range

# --------------------------------------------------------------------------
# Range parsing and the pool
# --------------------------------------------------------------------------


@pytest.mark.parametrize("raw,expected", [
    ("20000-29999", (20000, 29999)),
    (" 20000-20000 ", (20000, 20000)),
    (None, None),
    ("", None),
    ("20000", None),
    ("0-100", None),          # never uid 0
    ("300-200", None),        # reversed
    ("a-b", None),
    ("-5-10", None),
])
def test_uid_range_parsing(raw, expected) -> None:
    assert parse_uid_range(raw) == expected


def test_uid_range_setting_reads_the_environment(monkeypatch) -> None:
    monkeypatch.setenv("TOOL_SANDBOX_RUN_UID_RANGE", "20000-20009")
    assert Settings().run_uid_range == (20000, 20009)
    monkeypatch.delenv("TOOL_SANDBOX_RUN_UID_RANGE")
    assert Settings().run_uid_range is None


def test_seccomp_defaults_on(monkeypatch) -> None:
    monkeypatch.delenv("TOOL_SANDBOX_SECCOMP", raising=False)
    assert Settings().enable_seccomp is True


def test_pool_hands_out_distinct_uids_and_reuses_last() -> None:
    pool = proc.UidPool(20000, 20002)
    a, b = pool.acquire(), pool.acquire()
    assert (a, b) == (20000, 20001)
    pool.release(a)
    # 20002 was never used, so it comes before the just-released 20000.
    assert pool.acquire() == 20002
    assert pool.acquire() == 20000


def test_pool_exhaustion_is_an_error_not_a_shared_uid() -> None:
    pool = proc.UidPool(20000, 20000)
    pool.acquire()
    with pytest.raises(proc.UidPoolExhausted):
        pool.acquire()


def test_pool_is_thread_safe() -> None:
    pool = proc.UidPool(30000, 30999)
    got: list[int] = []
    lock = threading.Lock()

    def worker() -> None:
        for _ in range(100):
            uid = pool.acquire()
            with lock:
                got.append(uid)

    threads = [threading.Thread(target=worker) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(got) == len(set(got)) == 1000


def test_pool_rejects_uid_zero() -> None:
    with pytest.raises(ValueError):
        proc.UidPool(0, 10)


# --------------------------------------------------------------------------
# /proc scanning and kill-by-uid
# --------------------------------------------------------------------------

def _fake_proc(root: Path, entries: dict[int, tuple[int, str]]) -> Path:
    """entries: pid -> (uid, state letter)."""
    for pid, (uid, state) in entries.items():
        d = root / str(pid)
        d.mkdir(parents=True)
        (d / "status").write_text(
            f"Name:\tx\nState:\t{state} (x)\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n",
            encoding="ascii")
    (root / "self").mkdir(exist_ok=True)   # non-numeric entries are ignored
    return root


def test_uid_pids_finds_live_processes_of_that_uid_only(tmp_path) -> None:
    fake = _fake_proc(tmp_path, {
        10: (20000, "S"), 11: (20000, "R"), 12: (20001, "S"), 13: (20000, "Z"), 1: (0, "S"),
    })
    assert sorted(proc.uid_pids(20000, fake)) == [10, 11]   # zombie 13 skipped


def test_uid_pids_without_proc_is_empty(tmp_path) -> None:
    assert proc.uid_pids(20000, tmp_path / "missing") == []


def test_kill_uid_processes_kills_everything_including_setsid_escapees(tmp_path) -> None:
    # 10 is the run's leader, 11 a setsid()'d grandchild in its own session -
    # killpg on 10's group would miss it; kill-by-uid does not.
    fake = _fake_proc(tmp_path, {10: (20000, "S"), 11: (20000, "S"), 12: (20001, "S")})
    killed: list[tuple[int, int]] = []

    def kill(pid: int, sig: int) -> None:
        killed.append((pid, sig))
        for f in (fake / str(pid)).iterdir():
            f.unlink()
        (fake / str(pid)).rmdir()

    assert proc.kill_uid_processes(20000, proc_root=fake, kill=kill) == []
    assert sorted(pid for pid, _ in killed) == [10, 11]
    assert all(sig == 9 for _, sig in killed)
    assert (fake / "12").exists(), "another run's uid must be untouched"


def test_kill_uid_processes_reports_survivors(tmp_path, caplog) -> None:
    fake = _fake_proc(tmp_path, {10: (20000, "S")})

    def refuse(pid: int, sig: int) -> None:
        raise PermissionError()

    with caplog.at_level(logging.ERROR, logger=proc.log.name):
        survivors = proc.kill_uid_processes(20000, proc_root=fake, kill=refuse, passes=2)
    assert survivors == [10]
    assert any("EPERM" in r.getMessage() for r in caplog.records)


@pytest.mark.parametrize("uid", [0])
def test_kill_uid_processes_refuses_root(uid) -> None:
    with pytest.raises(ValueError):
        proc.kill_uid_processes(uid, kill=lambda p, s: None)


# --------------------------------------------------------------------------
# Runner: identity lifecycle and directory modes (as root, mocked)
# --------------------------------------------------------------------------

class _Recorder:
    def __init__(self) -> None:
        self.chown: list[tuple[str, int, int]] = []
        self.chmod: list[tuple[str, int]] = []
        self.killed: list[int] = []
        self.survivors: list[int] = []
        self.chown_error: Exception | None = None


@pytest.fixture
def as_root(monkeypatch, tmp_path):
    rec = _Recorder()
    monkeypatch.setattr(os, "geteuid", lambda: 0, raising=False)

    def fake_chown(path, uid, gid):
        if rec.chown_error:
            raise rec.chown_error
        rec.chown.append((str(path), uid, gid))

    def fake_chmod(path, mode):
        rec.chmod.append((str(path), mode))

    def fake_kill_uid(uid):
        rec.killed.append(uid)
        return list(rec.survivors)

    monkeypatch.setattr(os, "chown", fake_chown, raising=False)
    monkeypatch.setattr(os, "chmod", fake_chmod)
    monkeypatch.setattr(proc, "kill_uid_processes", fake_kill_uid)
    return rec


def _runner(tmp_path, uid_range=(20000, 20001)) -> runner.Runner:
    settings = Settings()
    settings.work_root = tmp_path / "work"
    settings.cache_root = tmp_path / "cache"
    settings.run_uid = 5001
    settings.run_gid = 5001
    settings.run_uid_range = uid_range
    return runner.Runner(settings)


def test_each_run_gets_its_own_uid_and_a_private_directory(as_root, tmp_path) -> None:
    r = _runner(tmp_path)
    with r._workdir("acme") as (work_a, ident_a), r._workdir("acme") as (work_b, ident_b):
        assert ident_a.per_run and ident_b.per_run
        assert ident_a.uid != ident_b.uid, "concurrent runs must not share a uid"
        assert ident_a.gid == ident_a.uid
        assert (str(work_a), ident_a.uid, 0) in as_root.chown
        assert (str(work_b), ident_b.uid, 0) in as_root.chown
        assert (str(work_a), isolation.RUN_DIR_MODE) in as_root.chmod
        # The tenant parent is traversable but not listable.
        assert (str(work_a.parent), isolation.TENANT_DIR_MODE) in as_root.chmod
    assert isolation.RUN_DIR_MODE == 0o750 and isolation.TENANT_DIR_MODE == 0o711
    assert sorted(as_root.killed) == [20000, 20001]
    assert r._isolation.uid_pool is not None and r._isolation.uid_pool.available() == 2
    assert not work_a.exists() and not work_b.exists()


def test_run_file_is_owned_by_the_run_uid(as_root, tmp_path) -> None:
    r = _runner(tmp_path)
    with r._workdir("acme") as (work, ident):
        result = work / "result.json"
        result.write_text("", encoding="utf-8")
        r._make_writable(result, ident)
    assert (str(result), ident.uid, 0) in as_root.chown
    assert (str(result), isolation.RUN_FILE_MODE) in as_root.chmod


def test_uid_with_surviving_processes_is_quarantined(as_root, tmp_path) -> None:
    r = _runner(tmp_path)
    as_root.survivors = [4242]
    with r._workdir("acme") as (_work, ident):
        pass
    assert as_root.killed == [ident.uid]
    assert r._isolation.uid_pool is not None and r._isolation.uid_pool.available() == 1


def test_shared_fallback_uid_is_never_mass_killed(as_root, tmp_path, caplog) -> None:
    with caplog.at_level(logging.WARNING, logger=isolation.log.name):
        r = _runner(tmp_path, uid_range=None)
    assert any("TOOL_SANDBOX_RUN_UID_RANGE is not set" in rec.getMessage()
               for rec in caplog.records)
    with r._workdir("acme") as (_work, ident):
        assert ident.uid == 5001 and not ident.per_run
    assert as_root.killed == [], "killing uid 5001 would kill other tenants' runs"


def test_missing_cap_chown_degrades_loudly_once(as_root, tmp_path, caplog) -> None:
    r = _runner(tmp_path)
    as_root.chown_error = PermissionError("Operation not permitted")
    with caplog.at_level(logging.ERROR, logger=isolation.log.name):
        with r._workdir("acme") as (work, ident):
            r._make_writable(work / "x", ident)
    errors = [rec for rec in caplog.records if "CAP_CHOWN" in rec.getMessage()]
    assert len(errors) == 1
    assert (str(work), 0o777) in as_root.chmod


def test_pool_exhaustion_becomes_a_clean_internal_error(as_root, tmp_path) -> None:
    r = _runner(tmp_path, uid_range=(20000, 20000))
    assert r._isolation.uid_pool is not None
    r._isolation.uid_pool.acquire()
    outcome = r.execute("python", "acme", "def run(input):\n    return 1\n", {}, 1000, 64)
    assert outcome.ok is False
    assert outcome.error_type == "INTERNAL"
    assert "capacity" in (outcome.error or "")
