"""Vault-secret plumbing: the env map, and that a tool actually receives one.

The end-to-end test at the bottom is the important one. Everything else here
could pass while the feature is still broken, because the interesting question
is not "did we build the right dict" but "can the tenant program read the value,
and can it read nothing else".
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.secretenv import SECRET_ENV_PREFIX, secret_env


class _Budget:
    max_secrets = 32
    max_secret_bytes = 8192


def test_no_secrets_produces_no_env_at_all():
    # The overwhelmingly common case. proc.run must be called exactly as it was
    # before this feature existed, so an empty dict (not a dict of empties).
    assert secret_env(None, _Budget()) == {}
    assert secret_env({}, _Budget()) == {}


def test_names_are_prefixed_so_the_harness_can_find_them_without_being_told():
    assert secret_env({"STRIPE_KEY": "sk_live_1"}, _Budget()) == {
        SECRET_ENV_PREFIX + "STRIPE_KEY": "sk_live_1"
    }


@pytest.mark.parametrize(
    "bad", ["lower_case", "1LEADING_DIGIT", "HAS-DASH", "HAS SPACE", "", "_UNDER"])
def test_a_name_core_could_not_have_stored_is_dropped_not_sanitised(bad):
    # core is the only writer of this field, so a mismatch means the two sides
    # have drifted. Quietly repairing it would hide that.
    assert secret_env({bad: "v"}, _Budget()) == {}


def test_budgets_drop_the_offending_entry_rather_than_failing_the_run():
    class Tight:
        max_secrets = 2
        max_secret_bytes = 8

    assert len(secret_env({"A_1": "v", "B_2": "v", "C_3": "v"}, Tight())) == 2
    assert secret_env({"BIG": "x" * 99}, Tight()) == {}


def test_non_string_values_are_ignored():
    assert secret_env({"NUM": 42}, _Budget()) == {}  # type: ignore[dict-item]


# --------------------------------------------------------------- end to end

HARNESS = Path(__file__).resolve().parents[1] / "harness" / "py_harness.py"

TOOL_READS_ITS_SECRET = """
def run(payload):
    return {"key": secrets["STRIPE_KEY"], "granted": sorted(secrets)}
"""

TOOL_TRIES_TO_READ_THE_ENVIRONMENT = """
def run(payload):
    import os
    return {"leaked": os.environ.get("VEGA_SECRET_STRIPE_KEY")}
"""


def _run_harness(tmp_path: Path, source: str, env_extra: dict[str, str]) -> dict:
    tool = tmp_path / "tool.py"
    tool.write_text(source, encoding="utf-8")
    inp = tmp_path / "input.json"
    inp.write_text("{}", encoding="utf-8")
    result = tmp_path / "result.json"
    result.write_text("", encoding="utf-8")

    env = {"PATH": os.environ.get("PATH", ""), **env_extra}
    subprocess.run(
        [sys.executable, "-I", "-S", "-B", str(HARNESS), str(tool), str(inp), str(result)],
        cwd=tmp_path, env=env, capture_output=True, timeout=60, check=False,
    )
    return json.loads(result.read_text(encoding="utf-8"))


def test_a_tool_can_read_a_granted_secret(tmp_path):
    out = _run_harness(
        tmp_path, TOOL_READS_ITS_SECRET, {SECRET_ENV_PREFIX + "STRIPE_KEY": "sk_live_abc123"})

    assert out["ok"] is True, out
    assert out["result"]["key"] == "sk_live_abc123"
    # It sees the names it was granted and nothing more - the allow-list is
    # applied by core before the process starts, not in here.
    assert out["result"]["granted"] == ["STRIPE_KEY"]


def test_a_tool_cannot_reach_the_environment_to_find_secrets_it_was_not_granted(tmp_path):
    out = _run_harness(
        tmp_path,
        TOOL_TRIES_TO_READ_THE_ENVIRONMENT,
        {SECRET_ENV_PREFIX + "STRIPE_KEY": "sk_live_abc123"},
    )

    # `import os` is refused by _guarded_import, so this never gets as far as
    # reading anything. Two defences stack here: the import guard, and the
    # harness having already popped the variable out of os.environ.
    assert out["ok"] is False, out
    assert "not permitted" in out["error"], out


def test_a_tool_with_no_grant_sees_an_empty_mapping_not_a_missing_name(tmp_path):
    out = _run_harness(tmp_path, "def run(payload):\n    return {'n': len(secrets)}\n", {})
    assert out["ok"] is True, out
    assert out["result"]["n"] == 0
