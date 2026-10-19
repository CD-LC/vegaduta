"""AST-level checks for tenant-authored Python tools.

Scope note, stated plainly because it matters: this is a *hardening* layer, not
a security boundary. CPython object graphs are richly interconnected and the
history of "safe eval" sandboxes is a history of escapes, so nobody should read
this file as proof that a hostile tool cannot reach the interpreter's internals.
What it does do is close the well-known routes (dunder traversal, dynamic
attribute lookup, dynamic import, exec/eval/compile, opening files) so that the
overwhelming majority of accidents and casual misuse fail early with a clear
message instead of at runtime.

The actual boundary for Python - as for Java - is
:mod:`app.proc`: a separate process, a separate unprivileged uid, a scrubbed
environment, hard rlimits, an optional seccomp filter that removes the socket
syscalls outright, and a container that holds no platform credentials and has
no route to the platform's data tier.

What this module rejects:

* imports outside :data:`policy.PYTHON_ALLOWED_MODULES`, and star imports;
* literal dunder attribute access (``x.__class__``), and since SEC-003 *any*
  attribute starting with ``_`` (``random._os`` is the real ``os`` module);
* attribute names in :data:`policy.PYTHON_DENIED_ATTRIBUTES` on *any* receiver
  and as a from-import name - this is what closes ``operator.attrgetter`` /
  ``methodcaller`` under every alias, the eval-backed ``typing`` helpers,
  ``string.Formatter.get_field`` and generator-frame walks;
* denied builtins by name (``getattr``, ``eval``, ``vars``, ``dir`` ...);
* string/bytes literals containing a dunder name (``"__class__"``,
  ``"{0.__class__}"``, a string annotation ``"().__class__"``). This is a cheap
  *extra* layer: every consumer that turns a string into an attribute lookup is
  already removed or denied above, so the literal check only matters if one of
  those is missed. It is deliberately literal-only - a dunder assembled at
  runtime (``"__cl" + "ass__"``, ``chr(95) * 2 + ...``) is out of scope for a
  syntactic check and is covered instead by there being nothing left to hand
  that string to. Docstrings (bare string statements, which are inert) and
  ``"__main__"`` are exempt.
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass

from . import policy

#: A dunder token inside a string: ``__class__`` in ``"{0.__class__}"``.
#: Anchored on non-identifier boundaries so ``"snake__case__name"`` does not
#: match, while ``"x.__class__"`` and ``"__class__"`` do.
_DUNDER_IN_TEXT = re.compile(r"(?<![A-Za-z0-9])__[A-Za-z][A-Za-z0-9_]*?__(?![A-Za-z0-9])")


@dataclass(frozen=True)
class Violation:
    line: int | None
    message: str


def validate(source: str) -> list[Violation]:
    """Return everything wrong with ``source``; empty means acceptable."""
    try:
        tree = ast.parse(source, filename="tool.py", mode="exec")
    except SyntaxError as exc:
        return [Violation(exc.lineno, f"Syntax error: {exc.msg}")]

    violations: list[Violation] = []
    visitor = _Checker(violations)
    visitor.visit(tree)

    if not visitor.found_run:
        violations.append(Violation(None, policy.PYTHON_ENTRYPOINT_HINT))
    return violations


class _Checker(ast.NodeVisitor):
    def __init__(self, out: list[Violation]) -> None:
        self.out = out
        self.found_run = False

    # -- entrypoint ------------------------------------------------------
    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        if node.col_offset == 0 and node.name == "run":
            self.found_run = True
        self.generic_visit(node)

    def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
        self.out.append(Violation(
            node.lineno,
            "async functions are not supported in a custom tool - define a plain `def run(input)`.",
        ))
        self.generic_visit(node)

    # -- imports ---------------------------------------------------------
    def visit_Import(self, node: ast.Import) -> None:
        for alias in node.names:
            self._check_module(alias.name, node.lineno)
        self.generic_visit(node)

    def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
        if node.level:
            self.out.append(Violation(
                node.lineno, "Relative imports are not permitted in a custom tool."))
        elif node.module:
            self._check_module(node.module, node.lineno)
        for alias in node.names:
            if alias.name == "*":
                # A star import would bring denied names (attrgetter, ...) into
                # scope without ever spelling them, so it is refused outright.
                self.out.append(Violation(
                    node.lineno,
                    "`from ... import *` is not permitted in a custom tool - import the "
                    "names you use explicitly.",
                ))
            elif alias.name.startswith("_"):
                self.out.append(Violation(
                    node.lineno,
                    f"Importing the private name `{alias.name}` is not permitted in a "
                    f"custom tool.",
                ))
            else:
                self._check_member_name(alias.name, node.lineno)
        self.generic_visit(node)

    def _check_module(self, name: str, line: int) -> None:
        parts = name.split(".")
        # Private submodules of an allowed package (zoneinfo._tzpath, re._parser)
        # are implementation detail, and several of them import os/sys.
        if parts[0] not in policy.PYTHON_ALLOWED_MODULES or any(
                p.startswith("_") for p in parts):
            allowed = ", ".join(sorted(policy.PYTHON_ALLOWED_MODULES))
            self.out.append(Violation(
                line,
                f"Import of `{name}` is not permitted in a custom tool. "
                f"A custom tool has no filesystem, network or process access. "
                f"Available modules: {allowed}.",
            ))

    # -- attribute access ------------------------------------------------
    def visit_Attribute(self, node: ast.Attribute) -> None:
        self._check_member_name(node.attr, node.lineno)
        self.generic_visit(node)

    def visit_MatchClass(self, node: ast.MatchClass) -> None:
        # `case int(__class__=c):` performs an attribute lookup by the keyword's
        # name, but the name is a bare identifier in the AST, not an Attribute
        # node - so it needs its own check.
        for attr in node.kwd_attrs:
            self._check_member_name(attr, node.lineno)
        self.generic_visit(node)

    def _check_member_name(self, attr: str, line: int) -> None:
        if attr.startswith("__") and attr.endswith("__"):
            self.out.append(Violation(
                line,
                f"Access to `{attr}` is not permitted - dunder attributes are the "
                f"standard route out of a Python sandbox.",
            ))
        elif attr.startswith("_"):
            # SEC-003: private attributes of library objects and modules are
            # where their own imports live (`random._os` IS the os module), and
            # the AST cannot tell a tool's own object from a library one - so
            # every underscore-prefixed attribute is refused, including on the
            # tool's own classes (use `self.count`, not `self._count`).
            self.out.append(Violation(
                line,
                f"Access to `{attr}` is not permitted - attributes starting with `_` are "
                f"private internals; name your own attributes without a leading underscore.",
            ))
        elif attr in policy.PYTHON_DENIED_ATTRIBUTES:
            self.out.append(Violation(
                line,
                f"`{attr}` is not permitted in a custom tool - it looks attributes up by "
                f"name at runtime or exposes interpreter frames, which would bypass the "
                f"sandbox's attribute rules.",
            ))

    # -- string literals ---------------------------------------------------
    def visit_Expr(self, node: ast.Expr) -> None:
        # A bare string statement (a docstring) is inert: nothing can receive
        # it, so dunder names mentioned in documentation are fine.
        if isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
            return
        self.generic_visit(node)

    def visit_Constant(self, node: ast.Constant) -> None:
        value = node.value
        if isinstance(value, bytes):
            text = value.decode("latin-1")
        elif isinstance(value, str):
            text = value
        else:
            return
        if text in policy.PYTHON_PERMITTED_DUNDER_STRINGS:
            return
        match = _DUNDER_IN_TEXT.search(text)
        if match:
            self.out.append(Violation(
                node.lineno,
                f"The string literal mentions `{match.group(0)}` - dunder names are not "
                f"permitted in a custom tool, even inside strings.",
            ))

    # -- names -----------------------------------------------------------
    def visit_Name(self, node: ast.Name) -> None:
        if node.id in policy.PYTHON_DENIED_NAMES:
            self.out.append(Violation(
                node.lineno, f"`{node.id}` is not available in a custom tool."))
        elif node.id in policy.PYTHON_DENIED_BUILTINS:
            self.out.append(Violation(node.lineno, _explain_builtin(node.id)))
        self.generic_visit(node)

    # -- class definitions ------------------------------------------------
    def visit_ClassDef(self, node: ast.ClassDef) -> None:
        # Subclassing an arbitrary base is how `object.__subclasses__()` style
        # traversals usually start; tools legitimately need only plain classes.
        for base in node.bases:
            if isinstance(base, ast.Name) and base.id == "object":
                self.out.append(Violation(
                    node.lineno,
                    "Explicitly subclassing `object` is not permitted - declare `class "
                    f"{node.name}:` instead.",
                ))
        self.generic_visit(node)


def _explain_builtin(name: str) -> str:
    reasons = {
        "open": "a custom tool has no filesystem access",
        "eval": "dynamic code execution would defeat every other restriction",
        "exec": "dynamic code execution would defeat every other restriction",
        "compile": "dynamic code execution would defeat every other restriction",
        "__import__": "imports are restricted to a fixed allowlist",
        "getattr": "dynamic attribute lookup can spell out a dunder at runtime",
        "setattr": "dynamic attribute assignment can patch objects out from under the sandbox",
        "globals": "it exposes the module namespace, including the sandbox's own scaffolding",
        "locals": "it exposes the module namespace, including the sandbox's own scaffolding",
        "vars": "it exposes object namespaces, including dunders",
        "super": "it exposes the class hierarchy",
        "object": "it is the root of the type graph used to enumerate every loaded class",
        "dir": "it enumerates dunder attributes",
    }
    reason = reasons.get(name, "it is not available in a custom tool")
    return f"`{name}` is not permitted in a custom tool - {reason}."
