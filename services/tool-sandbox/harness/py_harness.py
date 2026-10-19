"""Entry point for a custom-tool Python process. Trusted platform code.

Invoked as ``py_harness.py <toolFile> <inputFile> <resultFile>`` under
``python3 -I -S -B``:

* ``-I`` isolated mode - ignores ``PYTHON*`` environment variables and keeps the
  user site directory off ``sys.path``, so nothing outside this image can
  influence what gets imported;
* ``-S`` skips ``site``, which is what would otherwise pull in whatever happens
  to be installed;
* ``-B`` writes no ``.pyc`` files, so a tool cannot leave anything behind.

The result travels through a file rather than stdout, for the same reason the
Java harness does: the tool prints freely with ``print()``, and framing a
protocol message inside a stream the tool also writes to would let it forge its
own result.

Two independent gates run here, on top of the AST check the API already did
before this process started:

1. ``__import__`` is replaced with a version that consults the module
   allowlist. This is the gate that actually holds at runtime - the AST check
   can only see imports it can read syntactically, whereas nothing reaches a
   module without going through this function. It also hands the tool a
   read-only :class:`_ModuleProxy` instead of the module itself, because a
   module's attributes include whatever IT imported (``uuid.os``,
   ``random._os``, ``typing.sys``) - a plain, dunder-free attribute access the
   AST check cannot distinguish from ``uuid.uuid4``. The proxy hides private
   names, denied attribute names, and any submodule outside the allowlist.
2. ``__builtins__`` is replaced with a curated mapping, so ``open``, ``eval``,
   ``exec``, ``compile``, ``getattr`` and friends are simply absent from the
   tool's namespace rather than merely discouraged.

Neither is claimed to be airtight - see ``app/pyvalidate.py``'s module docstring.
The boundary is the process isolation in ``app/proc.py`` and the fact that this
container holds no platform credentials.
"""

from __future__ import annotations

import builtins
import json
import os
import sys
import types

_HARNESS_DIR = os.path.dirname(os.path.abspath(__file__))
_ROOT = os.path.dirname(_HARNESS_DIR)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from app import policy, pyvalidate  # noqa: E402 - path setup above must run first


def _module_allowed(module_name: str) -> bool:
    parts = module_name.split(".")
    return parts[0] in policy.PYTHON_ALLOWED_MODULES and not any(
        p.startswith("_") for p in parts)


def _name_hidden(name: str) -> bool:
    return name.startswith("_") or name in policy.PYTHON_DENIED_ATTRIBUTES


# Modules whose *functions* must not leak out through an allowed module's
# namespace (a public re-export like `somemod.system = os.system`). Checked by
# the defining module of any callable the proxy is about to hand over.
_DANGEROUS_ORIGINS = frozenset({
    "os", "posix", "nt", "posixpath", "ntpath", "sys", "subprocess", "_posixsubprocess",
    "importlib", "_imp", "io", "_io", "shutil", "socket", "_socket", "ctypes", "_ctypes",
    "pickle", "_pickle", "marshal", "signal", "_signal", "pty", "fcntl", "mmap",
})
_ALLOWED_BUILTIN_NAMES = frozenset(policy.PYTHON_ALLOWED_BUILTINS)


def _leaks_dangerous_callable(value: object) -> bool:
    if not callable(value) or isinstance(value, type):
        return False
    origin = getattr(value, "__module__", None)
    if not isinstance(origin, str):
        return False
    if origin == "builtins":
        # e.g. a module re-exporting `open` or `eval`; the allowed builtins
        # are already in the tool's namespace, so re-exports of them are fine.
        return getattr(value, "__name__", None) not in _ALLOWED_BUILTIN_NAMES
    return origin.split(".")[0] in _DANGEROUS_ORIGINS


# Proxy -> real module, keyed by the proxy's id. Held in the harness's own
# globals, which tenant code has no route to, so a proxy carries no attribute a
# tool could read to recover the module it wraps.
_PROXY_TARGETS: dict[int, types.ModuleType] = {}
_PROXIES: dict[str, _ModuleProxy] = {}


class _ModuleProxy:
    """Read-only view of an allowed module, as seen by tenant code."""

    __slots__ = ()

    def __getattribute__(self, name: str) -> object:
        module = _PROXY_TARGETS[id(self)]
        if _name_hidden(name):
            # Also covers __name__: without it, CPython's `from X import y`
            # fallback cannot reach around the proxy into sys.modules.
            raise AttributeError(
                f"'{module.__name__}.{name}' is not available in a custom tool")
        value = getattr(module, name)
        if isinstance(value, types.ModuleType):
            if not _module_allowed(value.__name__):
                raise AttributeError(
                    f"'{module.__name__}.{name}' is not available in a custom tool")
            return _proxy(value)
        if _leaks_dangerous_callable(value):
            raise AttributeError(
                f"'{module.__name__}.{name}' is not available in a custom tool")
        return value

    def __setattr__(self, name: str, value: object) -> None:
        raise AttributeError("modules are read-only in a custom tool")

    def __delattr__(self, name: str) -> None:
        raise AttributeError("modules are read-only in a custom tool")

    def __repr__(self) -> str:
        return f"<module '{_PROXY_TARGETS[id(self)].__name__}'>"


def _proxy(module: types.ModuleType) -> _ModuleProxy:
    existing = _PROXIES.get(module.__name__)
    if existing is not None:
        return existing
    wrapper = _ModuleProxy()
    _PROXY_TARGETS[id(wrapper)] = module
    _PROXIES[module.__name__] = wrapper
    return wrapper


def _guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
    if level != 0:
        raise ImportError("Relative imports are not permitted in a custom tool.")
    if not _module_allowed(name):
        raise ImportError(
            f"Import of '{name}' is not permitted in a custom tool. "
            f"A custom tool has no filesystem, network or process access."
        )
    for item in fromlist or ():
        if item == "*" or _name_hidden(item):
            raise ImportError(f"'from {name} import {item}' is not permitted in a custom tool.")
    return _proxy(_REAL_IMPORT(name, globals, locals, fromlist, level))


_REAL_IMPORT = builtins.__import__


def _safe_builtins() -> dict:
    allowed = {}
    for name in policy.PYTHON_ALLOWED_BUILTINS:
        if hasattr(builtins, name):
            allowed[name] = getattr(builtins, name)
    # `__build_class__` is not optional: CPython emits a call to it for every
    # `class` statement, so omitting it would make class definitions fail with
    # a baffling NameError rather than being "safer".
    allowed["__build_class__"] = builtins.__build_class__
    allowed["__import__"] = _guarded_import
    allowed["__name__"] = "builtins"
    return allowed


def _fail(result_path: str, error_type: str, message: str) -> None:
    _write(result_path, {"ok": False, "errorType": error_type, "error": message})
    sys.exit(0)


def _write(result_path: str, payload: dict) -> None:
    try:
        text = json.dumps(payload, default=str, allow_nan=False)
    except (TypeError, ValueError) as exc:
        text = json.dumps({
            "ok": False,
            "errorType": "RUNTIME",
            "error": f"The value returned by run() is not JSON-serializable: {exc}",
        })
    with open(result_path, "w", encoding="utf-8") as handle:
        handle.write(text)


def main(argv: list[str]) -> None:
    if len(argv) < 4:
        print("usage: py_harness.py <toolFile> <inputFile> <resultFile>", file=sys.stderr)
        raise SystemExit(2)
    tool_path, input_path, result_path = argv[1], argv[2], argv[3]

    # Bounded well below the point where CPython's own guard gets unreliable
    # under a tight RLIMIT_AS; a runaway recursion should raise, not segfault.
    sys.setrecursionlimit(500)

    with open(tool_path, encoding="utf-8") as handle:
        source = handle.read()

    violations = pyvalidate.validate(source)
    if violations:
        _fail(result_path, "VALIDATION", "; ".join(v.message for v in violations))

    with open(input_path, encoding="utf-8") as handle:
        raw_input_text = handle.read()
    try:
        parsed = json.loads(raw_input_text) if raw_input_text.strip() else {}
    except ValueError as exc:
        _fail(result_path, "RUNTIME", f"The input sent to this tool was not valid JSON: {exc}")
        return
    # A bare scalar or list still needs to be reachable, so it arrives under
    # "value" - matching the Java harness and the SpEL tools.
    tool_input = parsed if isinstance(parsed, dict) else {"value": parsed}

    # Vault secrets arrive as VEGA_SECRET_<NAME> environment variables. Read
    # them HERE, in the trusted harness, before any tenant code has run, then
    # remove them from os.environ so nothing later in this process can see
    # them - belt and braces, since the tenant program has no route to
    # os.environ anyway (no `os` import, no `open`, no `getattr`).
    #
    # Exposed as a plain `secrets` dict in the tool globals rather than as an
    # importable module: importing is guarded by _guarded_import and adding a
    # module would mean widening PYTHON_ALLOWED_MODULES, which is the list that
    # keeps this sandbox closed. A global needs no such widening.
    tool_secrets = {}
    for _env_name in [k for k in os.environ if k.startswith("VEGA_SECRET_")]:
        tool_secrets[_env_name[len("VEGA_SECRET_"):]] = os.environ.pop(_env_name)

    tool_globals: dict = {
        "__builtins__": _safe_builtins(),
        "__name__": "tool",
        "secrets": tool_secrets,
    }

    try:
        code = compile(source, "tool.py", "exec")
    except SyntaxError as exc:
        _fail(result_path, "COMPILE", f"Syntax error on line {exc.lineno}: {exc.msg}")
        return

    # Executing tenant code IS this process's job. It runs only after
    # pyvalidate.validate() above accepted the source, with `tool_globals`
    # holding the curated builtins and the guarded, proxying __import__, and
    # this whole process is the isolated child app/proc.py launched: dropped
    # uid, no-new-privs, rlimits, scrubbed env, no credentials, no network.
    try:
        # B102/S102 justified above: validated tenant code in the isolated child.
        exec(code, tool_globals)  # noqa: S102  # nosec B102
    except BaseException as exc:  # noqa: BLE001 - a tool may raise anything at import time
        _fail(result_path, "RUNTIME",
              f"Your tool failed while loading: {type(exc).__name__}: {exc}")
        return

    run = tool_globals.get("run")
    if not callable(run):
        _fail(result_path, "VALIDATION", policy.PYTHON_ENTRYPOINT_HINT)
        return

    try:
        value = run(tool_input)
    except BaseException as exc:  # noqa: BLE001 - surface the real error to the model
        _fail(result_path, "RUNTIME", f"Your tool threw {type(exc).__name__}: {exc}")
        return

    _write(result_path, {"ok": True, "result": value})


if __name__ == "__main__":
    main(sys.argv)
