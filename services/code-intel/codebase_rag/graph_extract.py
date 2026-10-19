"""Per-file symbol and edge extraction for the code graph (see graph.py).

Two deterministic engines: tree-sitter (definition, call, inheritance and
import nodes) and, where no grammar is available, the chunker's regex
definition patterns plus conservative regexes for imports, `name(` call
sites and extends/implements clauses. Output is unresolved: RawEdge carries
a target NAME; graph.py resolves names to symbols across the repo.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .chunker import _get_parser  # same lazy, cached parser loading
from .languages import (
    DEFINITION_TYPE_FRAGMENTS,
    FALLBACK_DEF_PATTERNS,
    IMPORT_PATTERNS,
    ts_grammar_for,
)

# ---------------------------------------------------------------------------
# data model
# ---------------------------------------------------------------------------

@dataclass
class Symbol:
    path: str
    language: str
    kind: str            # function | method | class | interface | struct | ...
    name: str
    qualified_name: str  # Outer.Inner.name within the file
    start_line: int      # 1-based inclusive
    end_line: int
    parent: str | None = None   # qualified_name of enclosing symbol


@dataclass
class RawEdge:
    src_qualified: str | None   # None => the file itself (imports)
    edge_type: str              # calls | imports | inherits | contains
    target_name: str            # unresolved name / module string
    line: int


@dataclass
class FileExtraction:
    path: str
    language: str
    engine: str                 # tree-sitter | regex
    symbols: list[Symbol] = field(default_factory=list)
    edges: list[RawEdge] = field(default_factory=list)


# Node-type fragments for call sites across tree-sitter grammars.
CALL_TYPE_FRAGMENTS = (
    "call_expression", "method_invocation", "function_call",
    "invocation_expression", "call",
)
# Inheritance clause node types across grammars.
INHERIT_TYPE_FRAGMENTS = (
    "superclass", "super_interfaces", "extends_clause", "implements_clause",
    "base_list", "superclasses", "class_heritage", "extends_type",
)

_KEYWORDS = frozenset("""
    if else for while switch catch return new delete typeof instanceof in of
    do try finally throw case break continue yield await async function class
    def lambda print assert raise with elif not and or is pass import from
    require include using namespace public private protected static final
    void int long float double bool boolean string var let const this super
    sizeof match when init until unless begin end defined echo select where
""".split())

_IDENT_CALL_RX = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")
_KIND_MAP = {
    "function": "function", "method": "method", "class": "class",
    "interface": "interface", "struct": "struct", "enum": "enum",
    "trait": "trait", "impl": "impl", "module": "module", "mod": "module",
    "type": "type", "record": "class", "constructor": "method",
    "object": "class", "protocol": "interface", "extension": "impl",
}

_INHERIT_FALLBACK_RX: dict[str, re.Pattern] = {
    "java": re.compile(r"(?:class|interface|enum|record)\s+(\w+)[^{]*?(?:extends|implements)\s+([\w\s,<>.]+)"),
    "csharp": re.compile(r"(?:class|interface|struct|record)\s+(\w+)[^{:]*:\s*([\w\s,<>.]+)"),
    "typescript": re.compile(r"class\s+(\w+)[^{]*?(?:extends|implements)\s+([\w\s,<>.]+)"),
    "tsx": re.compile(r"class\s+(\w+)[^{]*?(?:extends|implements)\s+([\w\s,<>.]+)"),
    "javascript": re.compile(r"class\s+(\w+)\s+extends\s+([\w.]+)"),
    "python": re.compile(r"class\s+(\w+)\s*\(([^)]*)\)"),
    "kotlin": re.compile(r"class\s+(\w+)[^{:]*:\s*([\w\s,<>().]+)"),
    "scala": re.compile(r"class\s+(\w+)[^{]*?extends\s+([\w\s,.]+)"),
    "php": re.compile(r"class\s+(\w+)[^{]*?(?:extends|implements)\s+([\w\s,\\\\]+)"),
    "ruby": re.compile(r"class\s+(\w+)\s*<\s*([\w:]+)"),
    "swift": re.compile(r"(?:class|struct)\s+(\w+)\s*:\s*([\w\s,.]+)"),
}


# ---------------------------------------------------------------------------
# extraction: tree-sitter engine
# ---------------------------------------------------------------------------

def _ts_extract(text: str, path: str, language: str) -> FileExtraction | None:
    grammar = ts_grammar_for(language)
    parser = _get_parser(grammar) if grammar else None
    if parser is None:
        return None
    source = text.encode("utf-8")
    try:
        tree = parser.parse(source)
    except Exception:
        return None

    fx = FileExtraction(path=path, language=language, engine="tree-sitter")

    def node_text(n) -> str:
        return source[n.start_byte:n.end_byte].decode("utf-8", errors="replace")

    def def_name(node) -> str | None:
        try:
            nn = node.child_by_field_name("name")
        except Exception:
            nn = None
        if nn is None:
            for ch in node.named_children:
                if "identifier" in ch.type or ch.type in ("name", "constant"):
                    nn = ch
                    break
        return node_text(nn) if nn is not None else None

    def kind_of(node) -> str:
        head = node.type.split("_")[0]
        return _KIND_MAP.get(head, head)

    def call_name(node) -> str | None:
        """Identifier being invoked: rightmost name of the callee part."""
        callee = None
        for f in ("function", "name", "constructor"):
            try:
                callee = node.child_by_field_name(f)
            except Exception:
                callee = None
            if callee is not None:
                break
        if callee is None and node.named_child_count:
            callee = node.named_children[0]
        if callee is None:
            return None
        txt = node_text(callee)
        m = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", txt)
        return m[-1] if m else None

    def walk(node, scope: list[str], enclosing: str | None):
        for child in node.named_children:
            t = child.type
            if any(f in t for f in DEFINITION_TYPE_FRAGMENTS):
                name = def_name(child)
                if name:
                    qname = ".".join(scope + [name])  # noqa: RUF005
                    sym = Symbol(
                        path=path, language=language, kind=kind_of(child),
                        name=name, qualified_name=qname,
                        start_line=child.start_point[0] + 1,
                        end_line=child.end_point[0] + 1,
                        parent=".".join(scope) if scope else None,
                    )
                    fx.symbols.append(sym)
                    if sym.parent:
                        fx.edges.append(RawEdge(sym.parent, "contains",
                                                qname, sym.start_line))
                    walk(child, scope + [name], qname)  # noqa: RUF005
                    continue
            if "import" in t or t in ("using_directive", "use_declaration",
                                      "require_call", "include"):
                for mod in re.findall(r"[\w./@-]+(?:\.[\w]+)*", node_text(child)):
                    if mod not in ("import", "from", "using", "use", "require",
                                   "include") and len(mod) > 1:
                        fx.edges.append(RawEdge(None, "imports", mod,
                                                child.start_point[0] + 1))
                        break  # first plausible module string per statement
                continue
            if any(f in t for f in INHERIT_TYPE_FRAGMENTS):
                for base in re.findall(r"[A-Za-z_][A-Za-z0-9_.]*", node_text(child)):
                    if base not in ("extends", "implements") and enclosing:
                        fx.edges.append(RawEdge(enclosing, "inherits", base,
                                                child.start_point[0] + 1))
                continue
            if any(f in t for f in CALL_TYPE_FRAGMENTS):
                cname = call_name(child)
                if cname and cname not in _KEYWORDS and enclosing:
                    fx.edges.append(RawEdge(enclosing, "calls", cname,
                                            child.start_point[0] + 1))
            walk(child, scope, enclosing)

    walk(tree.root_node, [], None)
    # Re-attribute calls to their innermost enclosing symbol by line span.
    _reattribute_calls(fx)
    return fx


def _reattribute_calls(fx: FileExtraction) -> None:
    """Walk() attributes calls to the def whose subtree it visited from,
    which for nested defs can be the outer one. Fix by line containment:
    a call belongs to the innermost symbol whose span contains its line."""
    spans = sorted(fx.symbols, key=lambda s: (s.end_line - s.start_line))
    for e in fx.edges:
        if e.edge_type != "calls":
            continue
        for s in spans:
            if s.start_line <= e.line <= s.end_line:
                e.src_qualified = s.qualified_name
                break


# ---------------------------------------------------------------------------
# extraction: regex fallback engine
# ---------------------------------------------------------------------------

def _rx_extract(text: str, path: str, language: str) -> FileExtraction:
    fx = FileExtraction(path=path, language=language, engine="regex")
    lines = text.split("\n")

    # The chunker's patterns anchor at column 0 (top-level defs are what
    # chunk boundaries need); the graph must also see indented members, so
    # allow leading whitespace without touching the shared pattern table.
    def_rx = None
    if language in FALLBACK_DEF_PATTERNS:
        pat = FALLBACK_DEF_PATTERNS[language]
        pat = pat[1:] if pat.startswith("^") else pat
        def_rx = re.compile(r"^[ \t]*(?:" + pat + r")")
    name_rx = re.compile(
        r"(?:def|class|function|fn|func|interface|enum|struct|trait|module|"
        r"object|record)\s+(\w+)|(\w+)\s*\(")

    # 1) symbols from definition-start lines; span = until next def at
    #    same-or-lower indentation (deterministic approximation)
    starts: list[tuple[int, str, int]] = []  # (line_idx, name, indent)
    if def_rx:
        for i, line in enumerate(lines):
            if def_rx.match(line):
                m = name_rx.search(line)
                name = next((g for g in (m.groups() if m else ()) if g), None)
                if name and name not in _KEYWORDS:
                    starts.append((i, name, len(line) - len(line.lstrip())))
    for j, (i, name, indent) in enumerate(starts):
        end = len(lines) - 1
        for k, (i2, _n2, ind2) in enumerate(starts[j + 1:], j + 1):  # noqa: B007
            if ind2 <= indent:
                end = i2 - 1
                break
        parents = [s for s in fx.symbols
                   if s.start_line - 1 < i and s.end_line - 1 >= i]
        parent = parents[-1].qualified_name if parents else None
        qname = f"{parent}.{name}" if parent else name
        kind = "class" if re.search(
            r"\b(class|interface|struct|enum|module|trait)\b", lines[i]) \
            else "function"
        fx.symbols.append(Symbol(path, language, kind, name, qname,
                                 i + 1, end + 1, parent))
        if parent:
            fx.edges.append(RawEdge(parent, "contains", qname, i + 1))

    # 2) imports
    imp_rx = re.compile(IMPORT_PATTERNS[language]) \
        if language in IMPORT_PATTERNS else None
    if imp_rx:
        for i, line in enumerate(lines[:300]):
            if imp_rx.match(line):
                mods = re.findall(r"['\"]([\w./@-]+)['\"]|([\w.]+\w)\s*;?\s*$",
                                  line.strip())
                for a, b in mods:
                    mod = a or b
                    if mod and mod not in ("import", "from", "use", "using"):
                        fx.edges.append(RawEdge(None, "imports", mod, i + 1))
                        break

    # 3) inheritance
    inh_rx = _INHERIT_FALLBACK_RX.get(language)
    if inh_rx:
        for i, line in enumerate(lines):
            m = inh_rx.search(line)
            if m:
                child, bases = m.group(1), m.group(2)
                child_syms = [s for s in fx.symbols if s.name == child]
                src = child_syms[0].qualified_name if child_syms else child
                for base in re.findall(r"[A-Za-z_][A-Za-z0-9_.]*", bases):
                    if base not in _KEYWORDS and base != "object":
                        fx.edges.append(RawEdge(src, "inherits", base, i + 1))

    # 4) call sites: name( occurrences inside each symbol's span,
    #    skipping the definition line itself and keywords
    for s in fx.symbols:
        if any(c.parent == s.qualified_name for c in fx.symbols):
            continue  # container: calls belong to its children (innermost)
        for i in range(s.start_line, s.end_line):  # skip the def line (s.start_line-1)
            if i >= len(lines):
                break
            for m in _IDENT_CALL_RX.finditer(lines[i]):
                name = m.group(1)
                if name not in _KEYWORDS and name != s.name:
                    fx.edges.append(RawEdge(s.qualified_name, "calls",
                                            name, i + 1))
    return fx


def extract_file(path: str, text: str, language: str) -> FileExtraction:
    fx = _ts_extract(text, path, language)
    if fx is None or (not fx.symbols and not fx.edges):
        fx_rx = _rx_extract(text, path, language)
        if fx is None or (fx_rx.symbols or fx_rx.edges):
            return fx_rx
    return fx
