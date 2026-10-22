"""Caller-controlled values reach the log with CR/LF removed.

``toolName`` (free text, up to 64 chars) and the request path are tenant-
or caller-controlled; if they were logged verbatim, one request could write a
second, forged log line.
"""

from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import main
from app.logsafe import clean
from app.models import ExecuteRequest
from app.outcome import ExecutionOutcome

FORGED = "x\r\n2026-01-01 00:00:00,000 INFO tool-sandbox forged line"


def test_clean_removes_carriage_returns_and_line_feeds():
    assert clean("a\r\nb\nc\rd") == "abcd"
    assert clean(42) == "42"
    assert "\n" not in clean(FORGED) and "\r" not in clean(FORGED)


def test_failed_execution_log_line_cannot_be_forged_through_toolname(monkeypatch, caplog):
    def failing_execute(*_args, **_kwargs):
        return ExecutionOutcome(False, error="boom", error_type="RUNTIME", duration_ms=3)

    monkeypatch.setattr(main._runner, "execute", failing_execute)
    request = ExecuteRequest(language="python", toolName=FORGED, source="x = 1")

    with caplog.at_level(logging.INFO, logger="tool-sandbox"):
        asyncio.run(main.execute(request, tenant="tenant-a"))

    messages = [r.getMessage() for r in caplog.records if "tool execution failed" in r.getMessage()]
    assert len(messages) == 1
    assert "\n" not in messages[0] and "\r" not in messages[0]
    assert "forged line" in messages[0]  # kept, but on the same line


def test_unhandled_error_log_line_cannot_be_forged_through_the_path(caplog):
    request = SimpleNamespace(url=SimpleNamespace(path="/v1/" + FORGED))

    with caplog.at_level(logging.ERROR, logger="tool-sandbox"):
        response = asyncio.run(main.unhandled(request, RuntimeError("internal")))

    assert response.status_code == 500
    messages = [r.getMessage() for r in caplog.records if "unhandled error" in r.getMessage()]
    assert len(messages) == 1
    assert "\n" not in messages[0] and "\r" not in messages[0]
