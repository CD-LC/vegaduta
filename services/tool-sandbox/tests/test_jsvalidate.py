"""Static-policy tests for JavaScript/TypeScript tool source.

Pure-Python: the identifier scan and the constructor-access denial run without
Node (the syntax half - ``node --check``-equivalent - is the runner's job and
is exercised by test_harness_e2e.py on hosts that have the runtime).
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import jsvalidate


def messages(source: str) -> list[str]:
    return [v.message for v in jsvalidate.validate(source)]


# --------------------------------------------------------------------------
# Acceptable programs
# --------------------------------------------------------------------------


def test_simple_run_function_accepted() -> None:
    assert messages("function run(input) { return { doubled: (input.n || 0) * 2 }; }") == []


def test_arrow_const_run_accepted() -> None:
    assert messages("const run = (input) => `hi ${input.name}`;") == []


def test_class_constructor_is_not_the_denied_pattern() -> None:
    # `constructor() {}` in a class body must NOT trip the `.constructor` denial.
    assert (
        messages(
            "class Box { constructor(v) { this.v = v; } }\n"
            "function run(input) { return new Box(input).v; }"
        )
        == []
    )


def test_denied_words_inside_string_and_comment_are_fine() -> None:
    assert (
        messages(
            "function run(i) { // require process eval\n"
            '  return "the word fetch, require and process are text here"; }'
        )
        == []
    )


def test_standard_library_is_fine() -> None:
    assert (
        messages(
            "function run(i) { const d = new Date(i.at); "
            "return { iso: d.toISOString(), id: crypto.randomUUID() }; }"
        )
        == []
    )


# --------------------------------------------------------------------------
# Denied identifiers
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    "name",
    sorted(
        [
            "require",
            "import",
            "process",
            "globalThis",
            "eval",
            "Function",
            "fetch",
            "WebSocket",
            "XMLHttpRequest",
            "navigator",
            "WebAssembly",
            "Worker",
            "Deno",
            "Bun",
            "__dirname",
            "__filename",
        ]
    ),
)
def test_denied_identifier_rejected(name: str) -> None:
    src = f"function run(i) {{ return {name}; }}"
    violations = jsvalidate.validate(src)
    assert violations, f"{name} should be denied"
    assert any(name in v.message for v in violations)


def test_identifier_suffix_is_not_flagged() -> None:
    # `processing`, `processEnv` etc are ordinary identifiers, not the denied token.
    assert (
        messages(
            "function run(i) { const processing = i.x; const processEnv = 1; "
            "return processing + processEnv; }"
        )
        == []
    )


def test_dynamic_import_rejected() -> None:
    violations = jsvalidate.validate(
        'function run(i) { const fs = await import("node:fs"); return 1; }'
    )
    assert any("`import`" in v.message for v in violations)


def test_proto_rejected() -> None:
    violations = jsvalidate.validate("function run(i) { return i.__proto__; }")
    assert any("__proto__" in v.message for v in violations)


# --------------------------------------------------------------------------
# .constructor access - the Function-constructor escape path
# --------------------------------------------------------------------------


def test_dotted_constructor_rejected() -> None:
    violations = jsvalidate.validate("function run(i) { return ({}).constructor; }")
    assert any("constructor" in v.message for v in violations)


def test_constructor_constructor_chain_rejected() -> None:
    violations = jsvalidate.validate(
        'function run(i) { return ({}).constructor.constructor("return 1")(); }'
    )
    assert violations


def test_bracketed_string_constructor_rejected() -> None:
    violations = jsvalidate.validate('function run(i) { return x["constructor"]; }')
    assert any("constructor" in v.message for v in violations)


def test_identifier_named_with_constructor_prefix_allowed() -> None:
    # `a.constructorField` is an ordinary property, not the escape.
    assert messages("function run(i) { return i.constructorField; }") == []


# --------------------------------------------------------------------------
# Template literals: ${...} is code
# --------------------------------------------------------------------------


def test_denied_name_inside_template_interpolation_rejected() -> None:
    violations = jsvalidate.validate("function run(i) { return `x ${process.env.Y} z`; }")
    assert any("process" in v.message for v in violations)


def test_plain_template_interpolation_fine() -> None:
    assert messages("function run(i) { return `${i.a + i.b}`; }") == []


# --------------------------------------------------------------------------
# Entrypoint contract
# --------------------------------------------------------------------------


def test_missing_run_rejected() -> None:
    violations = jsvalidate.validate("const x = 1;")
    assert any("run(input)" in v.message for v in violations)


def test_multiline_source_reports_line_numbers() -> None:
    violations = jsvalidate.validate(
        "// ok\nconst x = 1;\nfunction run(i) { return process.cwd(); }"
    )
    lines = [v.line for v in violations if v.line is not None]
    assert 3 in lines
