"""End-to-end tests for the two harnesses.

These actually run a tool and check what comes back, which is the only way to
catch the class of mistake unit tests cannot: a JVM that will not start under
``--limit-modules java.base``, a JSON round-trip that mangles a value, a
restricted-builtins mapping that is built but never applied.

They exercise the *harness contract*, not ``app/proc.py``. The uid drop and
rlimits depend on ``setpriv``/``prlimit`` and on POSIX semantics, so they are
covered by ``test_policy_and_proc.py`` at the command-composition level and are
meaningfully testable only on a Linux host.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

JAVA = shutil.which("java")
JAVAC = shutil.which("javac")


# --------------------------------------------------------------------------
# Java
# --------------------------------------------------------------------------

java_only = pytest.mark.skipif(
    JAVA is None or JAVAC is None, reason="java/javac are not on PATH")


@pytest.fixture(scope="module")
def java_runtime(tmp_path_factory) -> tuple[Path, Path]:
    """Compile the trusted harness exactly as the Dockerfile does."""
    if JAVAC is None:
        pytest.skip("javac is not on PATH")
    build = tmp_path_factory.mktemp("harness")
    api, internal = build / "api", build / "internal"
    api.mkdir()
    internal.mkdir()
    src = ROOT / "harness" / "java"
    subprocess.run([JAVAC, "-nowarn", "-d", str(api),
                    str(src / "vega" / "Log.java"),
                    str(src / "vega" / "Secrets.java")],
                   check=True, capture_output=True)
    subprocess.run(
        [JAVAC, "-nowarn", "-d", str(internal), "-cp", str(api),
         str(src / "vega" / "internal" / "Json.java"),
         str(src / "vega" / "internal" / "Harness.java")],
        check=True, capture_output=True)
    return api, internal


def run_java_tool(source: str, payload, java_runtime, tmp_path,
                  env_extra: dict[str, str] | None = None) -> tuple[dict, str]:
    api, internal = java_runtime
    classes = tmp_path / "classes"
    classes.mkdir()
    src = tmp_path / "Tool.java"
    src.write_text(source, encoding="utf-8")
    compiled = subprocess.run(
        [JAVAC, "-nowarn", "-g:lines,source", "-d", str(classes), "-cp", str(api), str(src)],
        capture_output=True, text=True)
    assert compiled.returncode == 0, compiled.stderr

    input_file = tmp_path / "input.json"
    result_file = tmp_path / "result.json"
    input_file.write_text(json.dumps(payload), encoding="utf-8")
    result_file.write_text("", encoding="utf-8")

    classpath = os.pathsep.join([str(internal), str(api), str(classes)])
    proc = subprocess.run(
        [JAVA, "--limit-modules", "java.base", "-XX:-UsePerfData",
         "-Xmx128m", "-cp", classpath, "vega.internal.Harness",
         str(input_file), str(result_file)],
        capture_output=True, text=True, timeout=60,
        env={**os.environ, **(env_extra or {})},
    )
    raw = result_file.read_text(encoding="utf-8")
    assert raw.strip(), f"harness wrote no result. stderr={proc.stderr}"
    return json.loads(raw), proc.stdout


@java_only
def test_java_tool_returns_its_value(java_runtime, tmp_path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Map.of("greeting", "Hello, " + input.get("name") + "!");
            }
        }
    """
    result, _ = run_java_tool(source, {"name": "Ada"}, java_runtime, tmp_path)
    assert result["ok"] is True
    assert result["result"] == {"greeting": "Hello, Ada!"}


@java_only
def test_java_tool_round_trips_types(java_runtime, tmp_path) -> None:
    source = """
        import java.util.List;
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return Map.of(
                    "echo", input.get("items"),
                    "count", ((List<?>) input.get("items")).size(),
                    "ratio", 0.5,
                    "flag", true,
                    "text", "quotes \\" and \\\\ backslash");
            }
        }
    """
    result, _ = run_java_tool(source, {"items": [1, 2, 3]}, java_runtime, tmp_path)
    assert result["ok"] is True
    value = result["result"]
    assert value["echo"] == [1, 2, 3]
    assert value["count"] == 3
    assert value["ratio"] == 0.5
    assert value["flag"] is True
    assert value["text"] == 'quotes " and \\ backslash'


@java_only
def test_java_tool_exception_becomes_a_readable_error(java_runtime, tmp_path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                throw new IllegalStateException("nope");
            }
        }
    """
    result, _ = run_java_tool(source, {}, java_runtime, tmp_path)
    assert result["ok"] is False
    assert result["errorType"] == "RUNTIME"
    # The author needs the type, the message and where it happened.
    assert "IllegalStateException" in result["error"]
    assert "nope" in result["error"]
    assert "Tool.run" in result["error"]


@java_only
def test_java_missing_entrypoint_is_explained(java_runtime, tmp_path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object execute(Map<String, Object> input) { return 1; }
        }
    """
    result, _ = run_java_tool(source, {}, java_runtime, tmp_path)
    assert result["ok"] is False
    assert "run(Map" in result["error"]


@java_only
def test_vega_log_goes_to_stdout_not_the_result(java_runtime, tmp_path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                vega.Log.info("a debug line");
                return "done";
            }
        }
    """
    result, stdout = run_java_tool(source, {}, java_runtime, tmp_path)
    assert result["result"] == "done"
    assert "a debug line" in stdout
    # The model must never see the author's debugging output.
    assert "a debug line" not in json.dumps(result["result"])


@java_only
def test_bare_scalar_input_is_reachable(java_runtime, tmp_path) -> None:
    source = """
        import java.util.Map;
        public class Tool {
            public static Object run(Map<String, Object> input) {
                return input.get("value");
            }
        }
    """
    result, _ = run_java_tool(source, 42, java_runtime, tmp_path)
    assert result["result"] == 42


# --------------------------------------------------------------------------
# Python
# --------------------------------------------------------------------------

def run_python_tool(source: str, payload, tmp_path) -> tuple[dict, str]:
    tool_file = tmp_path / "tool.py"
    input_file = tmp_path / "input.json"
    result_file = tmp_path / "result.json"
    tool_file.write_text(source, encoding="utf-8")
    input_file.write_text(json.dumps(payload), encoding="utf-8")
    result_file.write_text("", encoding="utf-8")

    proc = subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(ROOT / "harness" / "py_harness.py"),
         str(tool_file), str(input_file), str(result_file)],
        capture_output=True, text=True, timeout=60,
    )
    raw = result_file.read_text(encoding="utf-8")
    assert raw.strip(), f"harness wrote no result. stderr={proc.stderr}"
    return json.loads(raw), proc.stdout


def test_python_tool_returns_its_value(tmp_path) -> None:
    source = "def run(input):\n    return {'greeting': f\"Hello, {input['name']}!\"}\n"
    result, _ = run_python_tool(source, {"name": "Ada"}, tmp_path)
    assert result["ok"] is True
    assert result["result"] == {"greeting": "Hello, Ada!"}


def test_python_allowed_import_works_at_runtime(tmp_path) -> None:
    source = (
        "import math\n"
        "import json\n"
        "def run(input):\n"
        "    return {'root': math.sqrt(input['n']), 'json': json.dumps([1, 2])}\n"
    )
    result, _ = run_python_tool(source, {"n": 16}, tmp_path)
    assert result["ok"] is True
    assert result["result"]["root"] == 4.0


def test_python_denied_builtin_is_actually_absent_at_runtime(tmp_path) -> None:
    # `id` passes the AST check (it is not on the denied list) but is not in
    # PYTHON_ALLOWED_BUILTINS, so this proves the curated builtins mapping is
    # really applied rather than merely constructed.
    source = "def run(input):\n    return id(input)\n"
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result["ok"] is False
    assert "NameError" in result["error"]


def test_python_tool_exception_becomes_a_readable_error(tmp_path) -> None:
    source = "def run(input):\n    raise ValueError('nope')\n"
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result["ok"] is False
    assert result["errorType"] == "RUNTIME"
    assert "ValueError" in result["error"] and "nope" in result["error"]


def test_python_validation_runs_again_inside_the_harness(tmp_path) -> None:
    # Defence in depth: the API already rejected this, but the harness must not
    # rely on having been called correctly.
    source = "import os\ndef run(input):\n    return 1\n"
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result["ok"] is False
    assert result["errorType"] == "VALIDATION"


def test_python_print_goes_to_stdout_not_the_result(tmp_path) -> None:
    source = "def run(input):\n    print('a debug line')\n    return 'done'\n"
    result, stdout = run_python_tool(source, {}, tmp_path)
    assert result["result"] == "done"
    assert "a debug line" in stdout


@pytest.mark.parametrize("source", [
    # A module's own imports are plain attributes - `uuid.os` has no dunder
    # and no getattr, so only the runtime module proxy stops it (SVC-003).
    "import uuid\ndef run(input):\n    return str(uuid.os)\n",
    "import json.tool\ndef run(input):\n    return str(json.tool.sys)\n",
    "import calendar\ndef run(input):\n    return str(calendar.sys)\n",
])
def test_python_module_internals_are_hidden_at_runtime(source, tmp_path) -> None:
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result["ok"] is False
    assert "not available in a custom tool" in result["error"]


def test_python_private_module_attribute_is_rejected_before_running(tmp_path) -> None:
    # SEC-003: `random._os` IS the os module. Refused by the AST gate inside
    # the harness; the proxy would also refuse it at runtime.
    source = "import random\ndef run(input):\n    return random._os.getcwd()\n"
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result["ok"] is False
    assert result["errorType"] == "VALIDATION"
    assert "_os" in result["error"]


def test_python_from_import_of_a_modules_import_fails(tmp_path) -> None:
    result, _ = run_python_tool("from uuid import os\ndef run(input):\n    return 1\n",
                                {}, tmp_path)
    assert result["ok"] is False
    assert "ImportError" in result["error"]


def test_python_proxied_modules_still_work_normally(tmp_path) -> None:
    source = (
        "import collections.abc\n"
        "from collections import OrderedDict, abc\n"
        "import enum, operator\n"
        "from datetime import datetime, timezone\n"
        "class Colour(enum.Enum):\n"
        "    RED = 1\n"
        "def run(input):\n"
        "    return [isinstance({}, collections.abc.Mapping), isinstance({}, abc.Mapping),\n"
        "            list(OrderedDict(a=1)), Colour.RED.value, operator.add(2, 3),\n"
        "            datetime(2026, 1, 1, tzinfo=timezone.utc).year]\n"
    )
    result, _ = run_python_tool(source, {}, tmp_path)
    assert result == {"ok": True, "result": [True, True, ["a"], 1, 5, 2026]}


def test_python_unserializable_return_is_reported(tmp_path) -> None:
    source = "def run(input):\n    return {1, 2, 3}\n"
    result, _ = run_python_tool(source, {}, tmp_path)
    # json.dumps(default=str) turns the set into its text form rather than
    # failing the call - the author gets a usable answer either way.
    assert result["ok"] is True
    assert isinstance(result["result"], str)


# ------------------------------------------------------- vault secrets ------
# The Java counterpart of tests/test_secretenv.py's end-to-end pair. A tool
# cannot call System.getenv - the verifier denies java.lang.System outright -
# so vega.Secrets is the only route, and these pin that it works and that it
# hands over nothing the tool was not granted.

SECRET_ENV_PREFIX = "VEGA_SECRET_"

JAVA_TOOL_READS_ITS_SECRET = """
import java.util.Map;
import java.util.LinkedHashMap;

public class Tool {
    public static Object run(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", vega.Secrets.get("STRIPE_KEY"));
        out.put("has", vega.Secrets.has("STRIPE_KEY"));
        out.put("granted", vega.Secrets.names().size());
        return out;
    }
}
"""

JAVA_TOOL_ASKS_FOR_SOMETHING_IT_WAS_NOT_GRANTED = """
import java.util.Map;
import java.util.LinkedHashMap;

public class Tool {
    public static Object run(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("other", vega.Secrets.get("SOMEONE_ELSES_KEY"));
        out.put("has", vega.Secrets.has("SOMEONE_ELSES_KEY"));
        return out;
    }
}
"""


def test_java_tool_can_read_a_granted_secret(java_runtime, tmp_path):
    result, _ = run_java_tool(
        JAVA_TOOL_READS_ITS_SECRET, {}, java_runtime, tmp_path,
        env_extra={SECRET_ENV_PREFIX + "STRIPE_KEY": "sk_live_abc123"})

    assert result["ok"] is True, result
    assert result["result"]["key"] == "sk_live_abc123"
    assert result["result"]["has"] is True
    assert result["result"]["granted"] == 1


def test_java_tool_gets_null_for_a_secret_it_was_not_granted(java_runtime, tmp_path):
    result, _ = run_java_tool(
        JAVA_TOOL_ASKS_FOR_SOMETHING_IT_WAS_NOT_GRANTED, {}, java_runtime, tmp_path,
        env_extra={SECRET_ENV_PREFIX + "STRIPE_KEY": "sk_live_abc123"})

    assert result["ok"] is True, result
    # null, not "" - an empty credential reads as anonymous access to several
    # services, so a missing grant must be obviously missing.
    assert result["result"]["other"] is None
    assert result["result"]["has"] is False


def test_java_tool_with_no_grant_at_all_sees_an_empty_set(java_runtime, tmp_path):
    source = """
import java.util.Map;
public class Tool {
    public static Object run(Map<String, Object> input) {
        return vega.Secrets.names().size();
    }
}
"""
    result, _ = run_java_tool(source, {}, java_runtime, tmp_path)
    assert result["ok"] is True, result
    assert result["result"] == 0
