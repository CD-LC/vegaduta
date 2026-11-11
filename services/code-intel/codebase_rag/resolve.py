"""Call/inherit/reference edge resolution for the code graph (see graph.py).

Raw edges carry a target NAME and, for calls, the receiver; this module maps
them to a symbol in the repo or marks them external. Split out of graph.py so
the resolution rules can grow (and be tested) on their own.
"""

from __future__ import annotations

from .graph_extract import RawEdge

TYPE_KINDS = frozenset(
    {
        "class",
        "interface",
        "enum",
        "struct",
        "trait",
        "record",
        "impl",
        "object",
        "protocol",
        "type",
        "module",
        "namespace",
        "mod",
    }
)
_SELF_RECEIVERS = frozenset({"this", "self", "super", "cls", "base", "Self"})
# Method names that library types (collections, strings, streams, builders,
# file/IO handles, dicts) define everywhere. A call like `map.get(k)` or
# `sb.append(x)` on a variable must not become an edge to the one in-repo
# method that happens to share the name; only a stronger signal (same file,
# same class, a class-qualified call, an imported module) may claim it.
_UBIQUITOUS = frozenset(
    {
        "get",
        "set",
        "put",
        "add",
        "remove",
        "delete",
        "clear",
        "contains",
        "containsKey",
        "size",
        "length",
        "len",
        "isEmpty",
        "empty",
        "equals",
        "hashCode",
        "toString",
        "compareTo",
        "append",
        "extend",
        "insert",
        "pop",
        "push",
        "keys",
        "values",
        "items",
        "entries",
        "update",
        "copy",
        "map",
        "filter",
        "reduce",
        "forEach",
        "stream",
        "collect",
        "join",
        "split",
        "strip",
        "trim",
        "replace",
        "format",
        "write",
        "read",
        "close",
        "open",
        "flush",
        "next",
        "iterator",
        "hasNext",
        "of",
        "from",
        "valueOf",
        "parse",
        "stringify",
        "then",
        "catch",
        "finally",
        "log",
        "info",
        "debug",
        "warn",
        "error",
        "print",
        "println",
        "sort",
        "reverse",
        "index",
        "count",
        "find",
        "first",
        "last",
        "min",
        "max",
        "sum",
        "abs",
        "name",
        "id",
        "value",
        "type",
        "run",
        "start",
        "stop",
        "apply",
        "accept",
        "test",
        "call",
        "build",
        "builder",
        "create",
        "save",
        "load",
        "init",
        "execute",
        "handle",
        "process",
    }
)


class ResolveCtx:
    def __init__(self, symtab, classes, imports):
        self.symtab = symtab
        self.classes = classes
        self.imports = imports


def _dirname(p: str) -> str:
    return p.rsplit("/", 1)[0] if "/" in p else ""


def _imported(cand_path: str, mods: set[str]) -> bool:
    """True when one of the caller's import strings points at the candidate's
    file: the module's dotted/slashed tail equals the file stem or its path."""
    if not mods:
        return False
    stem = cand_path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
    norm = cand_path.rsplit(".", 1)[0].replace("/", ".")
    for m in mods:
        mm = m.strip("./").replace("/", ".")
        last = mm.rsplit(".", 1)[-1]
        if last == stem or mm.endswith(stem) or norm.endswith(mm) or (mm and mm in norm):
            return True
    return False


def _affine(receiver: str, cand: tuple[str, str]) -> bool:
    """The receiver variable's name echoes the candidate's owning class or file:
    `registry` ~ Registry.get / registry.py, `userRepo` ~ UserRepository.save."""
    r = receiver.lower().replace("_", "")
    if len(r) < 3:
        return False
    owner = cand[1].rsplit(".", 1)[0].lower().replace("_", "") if "." in cand[1] else ""
    stem = cand[0].rsplit("/", 1)[-1].rsplit(".", 1)[0].lower().replace("_", "")
    return any(r in hay or (len(hay) >= 3 and hay in r) for hay in (owner, stem) if hay)


def _pick(cands: list[tuple[str, str]], src_path: str) -> tuple[str, str]:
    """Deterministic best candidate: same directory first, then path order."""
    here = _dirname(src_path)
    return sorted(cands, key=lambda c: (_dirname(c[0]) != here, c[0], c[1]))[0]


def reresolve_unchanged(store, skip: set[str], symtab, ctx) -> int:
    """Re-run resolution for edges in files that were NOT re-extracted.

    A changed file can add, rename or delete symbols that unchanged files call:
    a call that was 'external' last time may now have a target, and one that
    pointed at a deleted symbol must stop pointing at it. The raw edge (name,
    receiver, line) is all `_resolve` needs, so this is a cheap SQLite pass
    instead of the weekly `--full` rebuild the old comment relied on."""
    updates = []
    for eid, sp, ss, et, tn, q, line, tp, ts, conf in store.conn.execute(
        "SELECT id, src_path, src_symbol, edge_type, target_name, qualifier, line, "
        "target_path, target_symbol, confidence FROM graph_edges "
        "WHERE edge_type IN ('calls','inherits','references')"
    ).fetchall():
        if sp in skip:
            continue
        r = resolve_edge(RawEdge(ss, et, tn, line, q), sp, symtab, ctx)
        if (r[4], r[5], r[6]) != (tp, ts, conf):
            updates.append((r[4], r[5], r[6], eid))
    store.conn.executemany(
        "UPDATE graph_edges SET target_path=?, target_symbol=?, confidence=? WHERE id=?", updates
    )
    return len(updates)


def resolve_edge(
    e: RawEdge,
    src_path: str,
    symtab: dict[str, list[tuple[str, str]]],
    ctx: ResolveCtx | None = None,
) -> tuple:
    """Scope rules, strongest signal first:

    1. receiver `this`/`self`/`super` -> a member of the caller's own class;
    2. same file -> 'local';
    3. class-qualified call (`Util.run()`, `Mod::f`) -> that class's member;
    4. the caller imports the candidate's file / same package -> 'global';
    5. a ubiquitous library name on a plain variable receiver -> 'external'
       (never guess an in-repo target for `map.get`);
    6. unique repo-wide match -> 'global'; several -> 'ambiguous' (nearest
       directory, then path order); none -> 'external'."""
    if e.edge_type in ("imports",):
        return (src_path, None, e.edge_type, e.target_name, None, None, "external", e.line)
    if e.edge_type == "contains":
        return (
            src_path,
            e.src_qualified,
            e.edge_type,
            e.target_name,
            src_path,
            e.target_name,
            "local",
            e.line,
        )

    def row(tp, tq, conf):
        return (src_path, e.src_qualified, e.edge_type, e.target_name, tp, tq, conf, e.line)

    short = e.target_name.split(".")[-1]
    candidates = symtab.get(short, [])
    qual = getattr(e, "qualifier", None)
    caller_class = (
        e.src_qualified.rsplit(".", 1)[0] if e.src_qualified and "." in e.src_qualified else None
    )

    same_file = [c for c in candidates if c[0] == src_path]
    if qual in _SELF_RECEIVERS and caller_class:
        own = [c for c in same_file if c[1] == f"{caller_class}.{short}"]
        if own:
            return row(own[0][0], own[0][1], "local")
    if same_file and not (
        qual
        and qual not in _SELF_RECEIVERS
        and qual != "?"
        and qual[:1].islower()
        and short in _UBIQUITOUS
    ):
        if caller_class:
            own = [c for c in same_file if c[1] == f"{caller_class}.{short}"]
            if own:
                return row(own[0][0], own[0][1], "local")
        return row(same_file[0][0], same_file[0][1], "local")

    if ctx is not None and qual and qual not in _SELF_RECEIVERS and qual != "?":
        # class-qualified: the receiver names a type/module in the repo
        typed = list(ctx.classes.get(qual, []))
        if typed:
            members = [c for c in candidates if any(c[1] == f"{t[1]}.{short}" for t in typed)]
            if len(members) >= 1:
                m = _pick(members, src_path)
                return row(m[0], m[1], "global" if len(members) == 1 else "ambiguous")
        # `Pattern.compile`, `Objects.requireNonNull`: a static call on a type
        # that is not defined in this repo is library code, whatever the name.
        if qual[:1].isupper() and not typed:
            return row(None, None, "external")
        # receiver is a plain variable: library names are not ours unless the
        # variable's name echoes the candidate's owner (`registry.get` -> Registry.get)
        if (qual[:1].islower() or qual == "?") and short in _UBIQUITOUS:
            kept = [c for c in candidates if _affine(qual, c)]
            if not kept:
                return row(None, None, "external")
            candidates = kept
    elif qual == "?" and short in _UBIQUITOUS:
        kept = [c for c in candidates if c[0] == src_path]
        if not kept:
            return row(None, None, "external")

    if candidates and ctx is not None:
        mods = ctx.imports.get(src_path, set())
        scoped = [
            c for c in candidates if _imported(c[0], mods) or _dirname(c[0]) == _dirname(src_path)
        ]
        if len(scoped) == 1:
            return row(scoped[0][0], scoped[0][1], "global")
        if scoped:
            m = _pick(scoped, src_path)
            return row(m[0], m[1], "ambiguous")
    if len(candidates) == 1:
        tp, tq = candidates[0]
        return row(tp, tq, "global")
    if candidates:
        tp, tq = _pick(candidates, src_path)
        return row(tp, tq, "ambiguous")
    return row(None, None, "external")
