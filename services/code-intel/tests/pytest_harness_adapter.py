"""Thin pytest adapter over the hand-rolled check() harness modules (SVC-015).

code-intel's tests are framework-free scripts: each tests/test_*.py has a
main() that prints ok/FAIL lines and returns (or sys.exit()s) a non-zero
code on any failure. CI runs them as `python -m tests.<module>`. This file
lets `pytest` (run from services/code-intel) drive exactly the same thing,
one pytest item per harness module, so pytest-based tooling (junit XML,
IDE runners, -k selection) works without rewriting ~200 checks.

Why a SUBPROCESS per module rather than import + main(): several harnesses
mutate process-global state at import time (CODE_INTEL_* env vars, fake
`docker`/`e2b` modules in sys.modules, SETTINGS patches), so running them in
one interpreter would make results order-dependent. A subprocess is also
byte-for-byte what CI executes.

Why pytest does NOT also collect the harness modules' own test_* functions:
pyproject.toml sets python_files to this file only. Those functions take no
fixtures but depend on module globals + main()'s ordering; collected on
their own they would "pass" while never asserting (check() only counts).

This module's name deliberately does not match tests/test_*.py, so the CI
loop `for f in tests/test_*.py; do python -m tests.$(basename ...)` never
runs it as if it were a harness.
"""

from __future__ import annotations

import glob
import os
import subprocess
import sys

import pytest

SERVICE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HARNESS_MODULES = sorted(
    os.path.splitext(os.path.basename(p))[0]
    for p in glob.glob(os.path.join(SERVICE_DIR, "tests", "test_*.py")))


@pytest.mark.parametrize("module", HARNESS_MODULES)
def test_harness_module(module: str) -> None:
    # B603: fixed argv - this interpreter plus a module name we globbed.
    proc = subprocess.run(  # nosec B603
        [sys.executable, "-m", f"tests.{module}"], cwd=SERVICE_DIR,
        capture_output=True, text=True, timeout=900)
    if proc.returncode != 0:
        tail = (proc.stdout[-6000:] + "\n--- stderr ---\n" + proc.stderr[-3000:])
        pytest.fail(f"tests.{module} exited {proc.returncode}\n{tail}", pytrace=False)
