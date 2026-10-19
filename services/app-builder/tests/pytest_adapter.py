"""Thin pytest adapter over app-builder's script-harness test modules.

The real tests here are framework-free scripts on a hand-rolled check()
harness: each tests/test_<name>.py has a main() that returns non-zero on any
failure, and CI runs them as `python -m tests.<name>`. pytest cannot collect
them directly (there are no test_* functions, so it would pass vacuously),
and pyproject.toml restricts collection to THIS file for that reason. It is
deliberately NOT named test_*.py, so CI's `for f in tests/test_*.py` loop
never tries to run it as a harness module.

Each module runs in its own subprocess, exactly as CI invokes it, so the
modules' global PASS/FAIL counters, env-var tweaks and sys.path edits cannot
leak into one another. A new tests/test_*.py is picked up automatically.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

SERVICE_DIR = Path(__file__).resolve().parents[1]
MODULES = sorted(p.stem for p in (SERVICE_DIR / "tests").glob("test_*.py"))


def test_harness_modules_discovered() -> None:
    assert MODULES, "no tests/test_*.py harness modules found"


@pytest.mark.parametrize("module", MODULES)
def test_harness_module_passes(module: str) -> None:
    proc = subprocess.run(
        [sys.executable, "-m", f"tests.{module}"],
        cwd=SERVICE_DIR,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=600,
        check=False,
    )
    assert proc.returncode == 0, (
        f"python -m tests.{module} exited {proc.returncode}\n"
        f"--- stdout (tail) ---\n{proc.stdout[-4000:]}\n"
        f"--- stderr (tail) ---\n{proc.stderr[-4000:]}"
    )
