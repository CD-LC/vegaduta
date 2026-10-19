"""Tests for the policy invariants and the launch-command composition.

``test_classfile.py`` proves the gate works on real bytecode. This file pins the
*reasoning* underneath it, so that a future edit to the allowlists cannot
quietly widen the boundary without a test going red - particularly the
per-method bootstrap carve-out, which is the single most delicate rule in the
policy.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import isolation, policy, proc, runner

# --------------------------------------------------------------------------
# The java.lang.invoke carve-out
# --------------------------------------------------------------------------

def test_bootstrap_carveout_is_per_method_not_per_class() -> None:
    # metafactory is how every lambda links, so it must be permitted...
    assert policy.java_member_allowed("java.lang.invoke.LambdaMetafactory", "metafactory")
    # ...but permitting it must NOT hand over the rest of the class. If this
    # ever flips to True, a tool could reach any denied class by name.
    assert not policy.java_member_allowed("java.lang.invoke.LambdaMetafactory", "findStatic")
    assert not policy.java_member_allowed("java.lang.invoke.MethodHandles", "lookup")
    assert not policy.java_member_allowed("java.lang.invoke.MethodHandles$Lookup", "findStatic")
    assert not policy.java_member_allowed("java.lang.invoke.MethodHandles$Lookup", "findClass")


def test_descriptor_only_types_are_never_call_targets() -> None:
    # Every type permitted in a descriptor must still be unreachable as an
    # owner - that split is what makes the loose descriptor check safe.
    for name in policy.JAVA_DESCRIPTOR_ONLY:
        assert policy.java_named_type_allowed(name), name
        if name not in {owner for owner, _ in policy.JAVA_ALLOWED_BOOTSTRAPS}:
            assert not policy.java_class_allowed(name), (
                f"{name} is reachable as a call target, not just nameable")


def test_class_object_is_nameable_but_inert() -> None:
    # Object.getClass() is allowlisted and returns a Class, so the type has to
    # be nameable; every method on it must remain unreachable.
    assert policy.java_named_type_allowed("java.lang.Class")
    assert not policy.java_class_allowed("java.lang.Class")
    assert not policy.java_member_allowed("java.lang.Class", "forName")
    assert not policy.java_member_allowed("java.lang.Class", "getClassLoader")


# --------------------------------------------------------------------------
# Denials beat allows
# --------------------------------------------------------------------------

@pytest.mark.parametrize("name", [
    "java.lang.System",
    "java.lang.Runtime",
    "java.lang.ProcessBuilder",
    "java.lang.Thread",
    "java.lang.ClassLoader",
    "java.io.File",
    "java.nio.file.Files",
    "java.net.Socket",
    "java.util.concurrent.Executors",   # inside an otherwise-allowed prefix
    "java.util.ServiceLoader",          # exact denial inside an allowed package
    "java.util.Scanner",
    "javax.script.ScriptEngineManager",
    "sun.misc.Unsafe",
    "jdk.internal.misc.Unsafe",
])
def test_denied_classes_are_unreachable(name: str) -> None:
    assert not policy.java_class_allowed(name)
    assert not policy.java_member_allowed(name, "anyMethod")
    assert not policy.java_named_type_allowed(name)


@pytest.mark.parametrize("name", [
    "java.lang.String",
    "java.lang.Math",
    "java.lang.Integer",
    "java.util.Map",
    "java.util.ArrayList",
    "java.util.stream.Collectors",
    "java.util.regex.Pattern",
    "java.math.BigDecimal",
    "java.time.LocalDate",
    "java.text.DecimalFormat",
    "vega.Log",
])
def test_permitted_classes_are_reachable(name: str) -> None:
    assert policy.java_class_allowed(name)
    assert policy.java_named_type_allowed(name)


def test_a_tools_own_classes_are_always_reachable() -> None:
    own = frozenset({"Tool", "Tool$Helper", "Tool$1"})
    assert policy.java_class_allowed("Tool$Helper", own)
    # ...but declaring a class into a denied namespace must not launder it.
    assert not policy.java_class_allowed("java.lang.System", own)


# --------------------------------------------------------------------------
# Python policy
# --------------------------------------------------------------------------

def test_input_is_absent_from_builtins_but_not_syntactically_denied() -> None:
    # `input` is the parameter name in the run(input) contract, so it must not
    # be flagged by name; the builtin is removed by omission instead.
    assert "input" not in policy.PYTHON_DENIED_BUILTINS
    assert "input" not in policy.PYTHON_ALLOWED_BUILTINS


@pytest.mark.parametrize("name", ["os", "sys", "subprocess", "socket", "importlib",
                                  "ctypes", "pickle", "shutil", "pathlib", "threading",
                                  "urllib", "http", "requests"])
def test_dangerous_modules_are_not_importable(name: str) -> None:
    assert name not in policy.PYTHON_ALLOWED_MODULES


def test_types_module_is_not_importable() -> None:
    # SVC-003: FunctionType/CodeType rebuild functions over arbitrary globals.
    assert "types" not in policy.PYTHON_ALLOWED_MODULES


@pytest.mark.parametrize("name", ["type", "vars", "dir", "globals", "locals",
                                  "getattr", "setattr", "object", "id"])
def test_introspection_builtins_are_absent_at_runtime(name: str) -> None:
    assert name not in policy.PYTHON_ALLOWED_BUILTINS


@pytest.mark.parametrize("name", ["attrgetter", "methodcaller", "get_type_hints",
                                  "ForwardRef", "Formatter", "mro", "gi_frame",
                                  "f_back", "f_globals", "tb_frame"])
def test_escape_attributes_are_denied(name: str) -> None:
    assert name in policy.PYTHON_DENIED_ATTRIBUTES


def test_no_allowed_builtin_is_also_denied() -> None:
    overlap = set(policy.PYTHON_ALLOWED_BUILTINS) & policy.PYTHON_DENIED_BUILTINS
    assert not overlap, f"contradictory policy for {overlap}"


# --------------------------------------------------------------------------
# Launch command composition
# --------------------------------------------------------------------------

LIMITS = proc.Limits(wall_ms=5000, cpu_seconds=6, max_output_bytes=65536, address_space_mb=128)


def test_command_drops_privileges_and_applies_limits(monkeypatch) -> None:
    monkeypatch.setattr(proc, "SETPRIV", "/usr/bin/setpriv")
    monkeypatch.setattr(proc, "PRLIMIT", "/usr/bin/prlimit")
    monkeypatch.setattr(os, "geteuid", lambda: 0, raising=False)

    command = proc.build_command(
        ["/usr/bin/python3", "tool.py"], LIMITS,
        run_uid=5001, run_gid=5001, require_privilege_drop=True)

    # setpriv must be the outermost program, so the uid drop happens before
    # anything else in the chain runs.
    assert command[0] == "/usr/bin/setpriv"
    assert "--reuid=5001" in command
    assert "--regid=5001" in command
    assert "--clear-groups" in command
    assert "--no-new-privs" in command
    assert "/usr/bin/prlimit" in command
    assert "--as=134217728" in command
    assert "--cpu=6" in command
    assert "--core=0" in command
    assert command[-2:] == ["/usr/bin/python3", "tool.py"]


def test_root_without_setpriv_refuses_to_run(monkeypatch) -> None:
    monkeypatch.setattr(proc, "SETPRIV", None)
    monkeypatch.setattr(proc, "PRLIMIT", "/usr/bin/prlimit")
    monkeypatch.setattr(os, "geteuid", lambda: 0, raising=False)

    with pytest.raises(proc.PrivilegeDropUnavailable):
        proc.build_command(["/usr/bin/python3"], LIMITS,
                           run_uid=5001, run_gid=5001, require_privilege_drop=True)


def test_root_with_no_configured_uid_refuses_to_run(monkeypatch) -> None:
    monkeypatch.setattr(proc, "SETPRIV", "/usr/bin/setpriv")
    monkeypatch.setattr(os, "geteuid", lambda: 0, raising=False)

    with pytest.raises(proc.PrivilegeDropUnavailable):
        proc.build_command(["/usr/bin/python3"], LIMITS,
                           run_uid=None, run_gid=None, require_privilege_drop=True)


def test_address_space_is_omitted_when_unset(monkeypatch) -> None:
    # The JVM case: RLIMIT_AS near the heap size stops it starting at all.
    monkeypatch.setattr(proc, "PRLIMIT", "/usr/bin/prlimit")
    monkeypatch.setattr(proc, "SETPRIV", None)
    monkeypatch.setattr(os, "geteuid", lambda: 1000, raising=False)

    limits = proc.Limits(wall_ms=5000, cpu_seconds=6, max_output_bytes=1024,
                         address_space_mb=None)
    command = proc.build_command(["java"], limits, run_uid=None, run_gid=None,
                                 require_privilege_drop=False)
    assert not any(arg.startswith("--as=") for arg in command)
    assert any(arg.startswith("--cpu=") for arg in command)


# --------------------------------------------------------------------------
# The timeout kill must report whether it actually worked
#
# Regression for a bug found by live container testing (2026-08-05): with all
# capabilities dropped, this container's root could not signal the uid-5001
# child, so `os.killpg` and `Popen.kill` both raised EPERM. The second one was
# uncaught, which turned every timeout into a 500 - and worse, the runaway
# program kept running. The fix is CAP_KILL in the compose entry; this test
# pins the reporting half, so a future capability change surfaces as a logged
# error instead of silently leaking processes again.
# --------------------------------------------------------------------------

class _FakeProc:
    def __init__(self, kill_error=None):
        self.pid = 4242
        self.kill_error = kill_error
        self.kill_called = False

    def kill(self):
        self.kill_called = True
        if self.kill_error:
            raise self.kill_error


@pytest.fixture
def posix_kill(monkeypatch):
    """Make the POSIX kill primitives patchable on any host.

    The service only ever runs on Linux, but these tests need to run wherever
    development happens - and on Windows ``signal.SIGKILL``, ``os.killpg`` and
    ``os.getpgid`` do not exist at all, so the function under test dies on an
    AttributeError before reaching the logic being asserted.
    """
    monkeypatch.setattr(proc.signal, "SIGKILL", 9, raising=False)
    monkeypatch.setattr(os, "getpgid", lambda pid: pid, raising=False)

    def set_killpg(behaviour):
        monkeypatch.setattr(os, "killpg", behaviour, raising=False)

    return set_killpg


def _raise(exc):
    def _fn(*args, **kwargs):
        raise exc
    return _fn


def test_kill_group_reports_failure_when_not_permitted(posix_kill) -> None:
    posix_kill(_raise(PermissionError()))
    assert proc._kill_group(_FakeProc(kill_error=PermissionError())) is False


def test_kill_group_reports_success_when_the_group_signal_lands(posix_kill) -> None:
    posix_kill(lambda pgid, sig: None)
    assert proc._kill_group(_FakeProc()) is True


def test_kill_group_falls_back_to_direct_kill(posix_kill) -> None:
    posix_kill(_raise(PermissionError()))
    fake = _FakeProc()
    assert proc._kill_group(fake) is True
    assert fake.kill_called


def test_already_exited_process_counts_as_killed(posix_kill) -> None:
    posix_kill(_raise(ProcessLookupError()))
    assert proc._kill_group(_FakeProc()) is True


def test_kill_group_signals_the_childs_own_group(posix_kill) -> None:
    # start_new_session=True makes the child its own group leader, so the
    # group id IS the pid - no getpgid lookup that could race an exiting child.
    calls = []
    posix_kill(lambda pgid, sig: calls.append((pgid, sig)))
    assert proc._kill_group(_FakeProc()) is True
    assert calls == [(4242, 9)]


class _FakeStdin:
    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        self.closed = True


def test_failed_kill_is_logged_as_degraded_and_closes_stdin(posix_kill, caplog) -> None:
    posix_kill(_raise(PermissionError()))
    fake = _FakeProc(kill_error=PermissionError())
    fake.stdin = _FakeStdin()
    with caplog.at_level("ERROR", logger=proc.log.name):
        assert proc._kill_group(fake) is False
    assert fake.stdin.closed
    errors = [r for r in caplog.records if r.levelname == "ERROR"]
    assert errors, "a failed kill must be logged at ERROR"
    assert "DEGRADED" in errors[0].getMessage()
    assert "CAP_KILL" in errors[0].getMessage()


# --------------------------------------------------------------------------
# Startup CAP_KILL self-check (SVC-008) - pure decision logic, runs on any host
# --------------------------------------------------------------------------

# Real /proc/self/status excerpts. 0x...20 has bit 5 (CAP_KILL) set; the
# compose entry's SETUID|SETGID|KILL is exactly 0xe0.
_STATUS_WITH_KILL = (
    "Name:\tpython3\nUid:\t0\t0\t0\t0\nCapInh:\t0\n"
    "CapPrm:\t00000000000000e0\nCapEff:\t00000000000000e0\n"
)
_STATUS_WITHOUT_KILL = "Name:\tpython3\nCapPrm:\t00000000000000c0\nCapEff:\t00000000000000c0\n"


def test_cap_eff_parser_reads_the_hex_mask() -> None:
    assert proc.parse_cap_eff(_STATUS_WITH_KILL) == 0xE0
    assert proc.parse_cap_eff("CapEff:\t000001ffffffffff\n") == 0x1FFFFFFFFFF
    assert proc.parse_cap_eff("Name:\tx\n") is None
    assert proc.parse_cap_eff("CapEff:\tnot-hex\n") is None


def test_cap_kill_bit_is_bit_five() -> None:
    assert proc.CAP_KILL == 5
    assert proc.has_capability(0xE0, proc.CAP_KILL)
    assert not proc.has_capability(0xC0, proc.CAP_KILL)
    assert not proc.has_capability(None, proc.CAP_KILL)


def test_root_with_cap_kill_passes_the_startup_check() -> None:
    proc.verify_kill_capability(require_privilege_drop=True, run_uid=5001,
                                allow_missing=False, euid=0,
                                status_text=_STATUS_WITH_KILL)


def test_root_without_cap_kill_fails_closed_at_startup() -> None:
    with pytest.raises(proc.KillCapabilityMissing, match="CAP_KILL"):
        proc.verify_kill_capability(require_privilege_drop=True, run_uid=5001,
                                    allow_missing=False, euid=0,
                                    status_text=_STATUS_WITHOUT_KILL)


def test_unreadable_capabilities_fail_closed() -> None:
    with pytest.raises(proc.KillCapabilityMissing, match="unreadable"):
        proc.verify_kill_capability(require_privilege_drop=True, run_uid=5001,
                                    allow_missing=False, euid=0, status_text="")


def test_explicit_override_starts_degraded_and_says_so(caplog) -> None:
    with caplog.at_level("ERROR", logger=proc.log.name):
        proc.verify_kill_capability(require_privilege_drop=True, run_uid=5001,
                                    allow_missing=True, euid=0,
                                    status_text=_STATUS_WITHOUT_KILL)
    assert any("DEGRADED" in r.getMessage() for r in caplog.records)


def test_no_cross_uid_signalling_needs_no_cap_kill() -> None:
    # Not root, or no run uid: the child shares our uid, so kill() just works.
    assert proc.kill_capability_problem(euid=1000, run_uid=5001,
                                        status_text=_STATUS_WITHOUT_KILL) is None
    assert proc.kill_capability_problem(euid=0, run_uid=None,
                                        status_text=_STATUS_WITHOUT_KILL) is None
    # Non-POSIX dev host (no geteuid at all).
    assert proc.kill_capability_problem(euid=None, run_uid=5001, status_text=None) is None


def test_not_requiring_privilege_drop_only_logs(caplog) -> None:
    with caplog.at_level("ERROR", logger=proc.log.name):
        proc.verify_kill_capability(require_privilege_drop=False, run_uid=5001,
                                    allow_missing=False, euid=0,
                                    status_text=_STATUS_WITHOUT_KILL)
    assert any("DEGRADED" in r.getMessage() for r in caplog.records)


def test_override_setting_is_off_by_default(monkeypatch) -> None:
    from app.settings import Settings

    monkeypatch.delenv("TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL", raising=False)
    assert Settings().allow_missing_cap_kill is False
    monkeypatch.setenv("TOOL_SANDBOX_ALLOW_MISSING_CAP_KILL", "true")
    assert Settings().allow_missing_cap_kill is True


@pytest.mark.skipif(not os.path.exists("/proc/self/status"),
                    reason="Linux-only: reads the real /proc/self/status")
def test_real_proc_status_has_a_parseable_cap_eff() -> None:
    with open("/proc/self/status", encoding="ascii") as handle:
        assert proc.parse_cap_eff(handle.read()) is not None


# --------------------------------------------------------------------------
# Tenant isolation of cached artifacts
# --------------------------------------------------------------------------

def test_tenant_cache_keys_are_distinct_and_stable() -> None:
    a, b = isolation.tenant_key("acme"), isolation.tenant_key("globex")
    assert a != b
    assert a == isolation.tenant_key("acme")
    # No path separators or traversal can survive the hashing.
    assert "/" not in a and "\\" not in a and ".." not in a
    assert isolation.tenant_key("../../etc").isalnum()


def test_identical_source_from_two_tenants_does_not_share_a_path() -> None:
    source = "def run(input):\n    return 1\n"
    digest = runner.sha256(source)
    assert runner.sha256(source) == digest  # stable
    assert isolation.tenant_key("acme") != isolation.tenant_key("globex")


# --------------------------------------------------------------------------
# javac error parsing
# --------------------------------------------------------------------------

def test_javac_errors_keep_line_numbers_and_drop_temp_paths() -> None:
    raw = (
        "/var/sandbox/work/abc/src/Tool.java:7: error: cannot find symbol\n"
        "        Runtime.getRuntime();\n"
        "        ^\n"
        "  symbol:   variable Runtime\n"
        "1 error\n"
    )
    errors = runner._parse_javac_errors(raw)
    assert errors == [{"line": 7, "message": "cannot find symbol"}]
    # The temp path means nothing to the author and must not be echoed back.
    assert "/var/sandbox" not in errors[0]["message"]


def test_unparseable_javac_output_still_produces_a_message() -> None:
    errors = runner._parse_javac_errors("something went sideways")
    assert len(errors) == 1
    assert errors[0]["message"]
