"""Turning a finished sandbox process into the outcome the API returns.

The harness writes ``{"ok": ..., "result" | "error", "errorType"}`` to a
result file; this module reads it back and classifies everything that can go
wrong around it (timeout, silent exit, oversized or unreadable result).
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from . import proc


@dataclass
class ValidationOutcome:
    valid: bool
    source_sha256: str
    errors: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


@dataclass
class ExecutionOutcome:
    ok: bool
    result: Any = None
    error: str | None = None
    error_type: str | None = None
    stdout: str = ""
    stderr: str = ""
    duration_ms: int = 0
    truncated: bool = False


def elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def interpret(
    run: proc.RunResult, result_file: Path, started: float, max_result_bytes: int
) -> ExecutionOutcome:
    duration = elapsed_ms(started)

    if run.timed_out:
        return ExecutionOutcome(
            False,
            error="This tool ran longer than its time limit and was stopped.",
            error_type="TIMEOUT",
            stdout=run.stdout,
            stderr=run.stderr,
            duration_ms=duration,
        )

    raw = ""
    try:
        raw = result_file.read_text(encoding="utf-8")
    except OSError:
        pass

    if not raw.strip():
        return ExecutionOutcome(
            False,
            error=_diagnose_silent_exit(run),
            error_type=_classify_silent_exit(run),
            stdout=run.stdout,
            stderr=run.stderr,
            duration_ms=duration,
        )

    if len(raw.encode("utf-8")) > max_result_bytes:
        return ExecutionOutcome(
            False,
            error=f"This tool returned more than {max_result_bytes} bytes. "
                  f"Return a summary or a smaller slice of the data.",
            error_type="OUTPUT_TOO_LARGE",
            stdout=run.stdout,
            stderr=run.stderr,
            duration_ms=duration,
        )

    try:
        payload = json.loads(raw)
    except ValueError:
        return ExecutionOutcome(
            False,
            error="The sandbox produced an unreadable result.",
            error_type="INTERNAL",
            stdout=run.stdout,
            stderr=run.stderr,
            duration_ms=duration,
        )

    if payload.get("ok"):
        return ExecutionOutcome(
            True,
            result=payload.get("result"),
            stdout=run.stdout,
            stderr=run.stderr,
            duration_ms=duration,
            truncated=run.stdout_truncated,
        )

    error_type = payload.get("errorType") or "RUNTIME"
    message = payload.get("error") or "The tool failed."
    if "MemoryError" in message or "OutOfMemoryError" in message:
        error_type = "MEMORY"
    return ExecutionOutcome(
        False,
        error=message,
        error_type=error_type,
        stdout=run.stdout,
        stderr=run.stderr,
        duration_ms=duration,
    )


def _classify_silent_exit(run: proc.RunResult) -> str:
    combined = f"{run.stderr}\n{run.stdout}"
    if "OutOfMemoryError" in combined or "MemoryError" in combined:
        return "MEMORY"
    # SIGKILL with no message is what an out-of-memory kill or an RLIMIT_FSIZE
    # violation looks like from here.
    if run.signalled in (9, 25):
        return "MEMORY"
    return "RUNTIME"


def _diagnose_silent_exit(run: proc.RunResult) -> str:
    if _classify_silent_exit(run) == "MEMORY":
        return "This tool ran out of memory and was stopped."
    detail = (run.stderr or run.stdout or "").strip()
    if detail:
        return f"This tool exited without returning a result: {detail[:1000]}"
    return "This tool exited without returning a result."
