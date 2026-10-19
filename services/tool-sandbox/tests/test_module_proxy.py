"""SEC-003: the runtime module proxy the Python harness hands to tenant code.

A module's attributes include whatever that module itself imported
(``uuid.os``, ``random._os``, ``dataclasses.builtins``), and reaching one of
those is a plain, dunder-free attribute access. These tests enumerate EVERY
allow-listed module and assert that nothing module-valued, private, or
re-exported from os/sys/subprocess/builtins is reachable through the proxy -
so adding a module to ``policy.PYTHON_ALLOWED_MODULES`` is automatically
covered.

The harness is loaded in-process here (it is also exercised end-to-end, in a
real subprocess, by ``test_harness_e2e.py``).
"""

from __future__ import annotations

import importlib
import importlib.util
import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import policy

ROOT = Path(__file__).resolve().parents[1]

_spec = importlib.util.spec_from_file_location(
    "py_harness_under_test", ROOT / "harness" / "py_harness.py"
)
assert _spec is not None and _spec.loader is not None
harness = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(harness)

ALLOWED = sorted(policy.PYTHON_ALLOWED_MODULES)
DANGEROUS_NAMES = (
    "os",
    "sys",
    "subprocess",
    "builtins",
    "bltns",
    "importlib",
    "io",
    "shutil",
    "socket",
    "ctypes",
    "pickle",
    "_os",
    "_sys",
)


def _reachable(proxy: object, name: str) -> tuple[bool, object]:
    try:
        return True, getattr(proxy, name)
    except AttributeError:
        return False, None


@pytest.mark.parametrize("module_name", ALLOWED)
def test_no_real_module_or_dangerous_callable_is_reachable(module_name: str) -> None:
    real = importlib.import_module(module_name)
    proxy = harness._guarded_import(module_name)
    assert not isinstance(proxy, types.ModuleType)
    for attr in dir(real):
        ok, value = _reachable(proxy, attr)
        if not ok:
            continue
        assert not attr.startswith("_"), f"{module_name}.{attr} (private) is reachable"
        assert not isinstance(value, types.ModuleType), (
            f"{module_name}.{attr} hands out the real module {value!r}"
        )
        assert not harness._leaks_dangerous_callable(value), (
            f"{module_name}.{attr} re-exports a dangerous callable"
        )
        if isinstance(value, harness._ModuleProxy):
            # One level down: submodules come back proxied, and are held to
            # the same rules.
            for sub_attr in DANGEROUS_NAMES:
                assert not _reachable(value, sub_attr)[0], (
                    f"{module_name}.{attr}.{sub_attr} is reachable"
                )


@pytest.mark.parametrize("module_name", ALLOWED)
@pytest.mark.parametrize("attr", DANGEROUS_NAMES)
def test_os_sys_subprocess_builtins_are_never_reachable(module_name: str, attr: str) -> None:
    proxy = harness._guarded_import(module_name)
    ok, value = _reachable(proxy, attr)
    if not ok:
        return
    # A same-named attribute that is NOT the module is fine (typing.io is
    # typing's own IO/TextIO alias namespace on <= 3.12, not the io module).
    dangerous = {sys.modules.get(n) for n in DANGEROUS_NAMES} - {None}
    assert not isinstance(value, types.ModuleType), f"{module_name}.{attr} is a module"
    assert all(value is not m for m in dangerous), f"{module_name}.{attr} is reachable"


@pytest.mark.parametrize(
    "module_name,attr",
    [
        ("random", "_os"),
        ("uuid", "os"),
        ("uuid", "sys"),
        ("calendar", "sys"),
        ("dataclasses", "builtins"),
        ("dataclasses", "sys"),
        ("enum", "bltns"),
        ("typing", "sys"),
        ("re", "_compiler"),
        ("json", "codecs"),
        ("operator", "attrgetter"),
        ("operator", "methodcaller"),
        ("typing", "get_type_hints"),
        ("string", "Formatter"),
    ],
)
def test_known_leaks_are_closed(module_name: str, attr: str) -> None:
    proxy = harness._guarded_import(module_name)
    if not hasattr(importlib.import_module(module_name), attr):
        # The leak does not exist on this Python (dataclasses stopped
        # importing builtins after 3.11): the lookup still fails, just with
        # the module's own AttributeError.
        with pytest.raises(AttributeError):
            getattr(proxy, attr)
        return
    with pytest.raises(AttributeError, match="not available in a custom tool"):
        getattr(proxy, attr)


def test_from_import_goes_through_the_same_gate() -> None:
    # `from uuid import os` calls __import__('uuid', fromlist=('os',)) and then
    # getattr(<proxy>, 'os'); private and denied names are refused up front.
    proxy = harness._guarded_import("uuid", fromlist=("uuid4",))
    assert callable(proxy.uuid4)
    with pytest.raises(AttributeError):
        _ = proxy.os
    for bad in ("_os", "attrgetter", "*"):
        with pytest.raises(ImportError):
            harness._guarded_import("random" if bad == "_os" else "operator", fromlist=(bad,))


def test_proxies_are_read_only() -> None:
    proxy = harness._guarded_import("math")
    with pytest.raises(AttributeError):
        proxy.pi = 3  # type: ignore[attr-defined]
    with pytest.raises(AttributeError):
        del proxy.pi


@pytest.mark.parametrize(
    "name",
    ["os", "sys", "subprocess", "types", "importlib", "zoneinfo._tzpath", "re._parser", "builtins"],
)
def test_denied_and_private_modules_do_not_import(name: str) -> None:
    with pytest.raises(ImportError):
        harness._guarded_import(name)


def test_public_api_still_works_through_the_proxy() -> None:
    math = harness._guarded_import("math")
    assert math.sqrt(16) == 4.0
    collections = harness._guarded_import("collections.abc")
    assert isinstance({}, collections.abc.Mapping)
    datetime = harness._guarded_import("datetime", fromlist=("date",))
    assert datetime.date(2026, 1, 2).isoformat() == "2026-01-02"
