"""Log-line hygiene: caller-controlled values must not forge log records.

A value that came from a request (a project id on a not-yet-validated path, a
Caddy admin error that echoes a request body, ...) is written to the log only
after CR/LF are removed, so it cannot start a fake second line.
"""

from __future__ import annotations


def clean(value: object) -> str:
    """`str(value)` with carriage returns and line feeds removed."""
    return str(value).replace("\r", "").replace("\n", "")
