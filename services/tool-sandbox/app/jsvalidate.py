"""Static analysis for JavaScript/TypeScript tool source.

JS has no compile step we can verify output of, so this module is the static
layer of the JS sandbox: a tokeniser that blanks out comments and string/
template literals, then scans what is left for the identifiers and access
patterns in :mod:`policy` that would reach the runtime's machinery - ``require``
/``import`` (the module system), ``process`` (the environment and process
control), the eval family, and the network globals. ``.constructor`` access is
denied separately on the RAW source, including inside string literals, because
``x["constructor"]`` / ``x.constructor`` are the standard path to
``Function(...)`` and none of it survives the identifier scan.

The syntax check itself lives in ``runner`` (``node --check`` needs the binary,
and this module deliberately stays pure so it can be unit-tested without Node).

Honest scope, matching ``policy.py``'s own warning: this is the first layer,
not the only one. The real boundaries are Node's ``--experimental-permission``
(fs/child_process/worker/addons/wasi calls throw), the uid drop, the rlimits
and the empty credential surface.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from . import policy

# Matches a `function run(` declaration, `const|let|var run =`, or
# `run(` as an object shorthand - any way a tenant can declare the entrypoint.
_RUN_DECL = re.compile(r"(?:^|[^\w$])(?:function\s+run\s*\(|(?:const|let|var)\s+run\s*=|run\s*\()")

_IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")

_DENIED_ACCESS = re.compile(policy.JS_DENIED_ACCESS_RE)


@dataclass(frozen=True)
class Violation:
    line: int | None
    message: str


def validate(source: str) -> list[Violation]:
    """Return everything wrong with ``source``; empty means acceptable."""
    violations: list[Violation] = []

    for m in _DENIED_ACCESS.finditer(source):
        violations.append(
            Violation(
                _line_of(source, m.start()),
                "Property access to `constructor` is not permitted in a custom tool "
                "(it reaches the Function constructor, which bypasses the import "
                "restrictions).",
            )
        )

    stripped = _blank_noncode(source)
    for m in _IDENT.finditer(stripped):
        if m.group(0) in policy.JS_DENIED_IDENTIFIERS:
            violations.append(
                Violation(
                    _line_of(source, m.start()),
                    f"`{m.group(0)}` is not permitted in a custom tool - sandboxed "
                    "code has no module system, process environment or network.",
                )
            )

    if not _RUN_DECL.search(stripped):
        violations.append(Violation(None, policy.JS_ENTRYPOINT_HINT))

    return violations


def _line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def _blank_noncode(source: str) -> str:
    """Return ``source`` with comments and string/template/regex literals replaced
    by spaces (newlines preserved so reported line numbers stay correct).

    A small hand-rolled scanner rather than a full parser: the sandbox image
    carries no JS parser for Python to call, and identifier scanning only needs
    to know which characters are code. Regex literals are treated as division
    - an over-approximation (the `*` inside `/.../` could split a token), which
    is the safe direction: it can never *join* two identifiers into a denied
    word that was not written.
    """
    out = list(source)
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if c == "/" and i + 1 < n and source[i + 1] == "/":
            j = i
            while j < n and source[j] != "\n":
                j += 1
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and i + 1 < n and source[i + 1] == "*":
            j = i + 2
            while j + 1 < n and not (source[j] == "*" and source[j + 1] == "/"):
                j += 1
            end = min(j + 2, n)
            for k in range(i, end):
                if source[k] != "\n":
                    out[k] = " "
            i = end
        elif c == "`":
            # Template literal: blank the string parts but keep `${...}`
            # interpolations - they are real code and can carry denied names.
            out[i] = " "
            j = i + 1
            while j < n and source[j] != "`":
                if source[j] == "\\":
                    # The escaped char stays whatever it was (a "\n" keeps its
                    # newline); the backslash itself is blanked.
                    out[j] = " "
                    j += 2
                    continue
                if source[j] == "$" and j + 1 < n and source[j + 1] == "{":
                    out[j] = out[j + 1] = " "
                    depth, k = 1, j + 2
                    while k < n and depth:
                        if source[k] == "{":
                            depth += 1
                        elif source[k] == "}":
                            depth -= 1
                        k += 1
                    end = min(k, n)
                    # The interpolation is code - copy it through (and blank
                    # its closing brace), then keep scanning as a literal.
                    for t in range(j + 2, end - 1):
                        out[t] = source[t]
                    if end > 0:
                        out[end - 1] = " "
                    j = end
                    continue
                if source[j] != "\n":
                    out[j] = " "
                j += 1
            if j < n:
                out[j] = " "  # the closing backtick
            i = min(j + 1, n)
        elif c in "'\"":
            quote = c
            j = i + 1
            while j < n:
                if source[j] == "\\":
                    j += 2
                    continue
                if source[j] == quote:
                    break
                j += 1
            end = min(j + 1, n)
            for k in range(i, end):
                if source[k] != "\n":
                    out[k] = " "
            i = end
        else:
            i += 1
    return "".join(out)
