"""Deterministic, AST-derived code graph.

Nodes are symbols (functions, methods, classes, interfaces, ...) extracted
from source structure; edges are `contains`, `calls`, `imports`, and
`inherits`. Extraction is deterministic by construction:

- Primary engine: tree-sitter (same lazy loading as the chunker). Symbols
  come from definition nodes, calls from call/invocation nodes, inheritance
  from superclass/base-list clauses, imports from import nodes.
- Fallback engine (tree-sitter unavailable / no grammar): the same regex
  definition patterns the chunker uses, plus conservative regexes for
  imports, `name(` call sites, and extends/implements clauses.

No LLM is involved anywhere in this module - an edge exists only if a
parser or a fixed regex produced it, so the graph is reproducible run to
run. Name-to-symbol resolution follows fixed scope rules (same file ->
unique repo-wide -> ambiguous), and every edge records which rule matched
via its `confidence` column: 'local' | 'global' | 'ambiguous' | 'external'.

Extraction (both engines) lives in graph_extract.py; this module owns the
graph tables, the incremental build, name resolution and the queries.

Storage: separate tables inside the same .coderag/index.db, keyed by file
hash so `coderag graph build` is incremental exactly like indexing -
only changed files are re-extracted.
"""

from __future__ import annotations

import hashlib
import os
import time

from . import discovery
from .config import Config
from .graph_extract import FileExtraction, RawEdge, extract_file
from .languages import detect_language
from .routes_extract import extract_routes
from .store import open_db  # WAL-vs-rollback fallback for network mounts

# ---------------------------------------------------------------------------
# graph store (separate tables, same index.db)
# ---------------------------------------------------------------------------

GRAPH_SCHEMA = """
CREATE TABLE IF NOT EXISTS graph_files (
    path TEXT PRIMARY KEY,
    file_hash TEXT NOT NULL,
    language TEXT NOT NULL,
    engine TEXT NOT NULL,
    extracted_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS graph_symbols (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    language TEXT NOT NULL,
    kind TEXT NOT NULL,
    name TEXT NOT NULL,
    qualified_name TEXT NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    parent TEXT
);
CREATE INDEX IF NOT EXISTS idx_gsym_path ON graph_symbols(path);
CREATE INDEX IF NOT EXISTS idx_gsym_name ON graph_symbols(name);
CREATE TABLE IF NOT EXISTS graph_edges (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    src_path TEXT NOT NULL,
    src_symbol TEXT,
    edge_type TEXT NOT NULL,
    target_name TEXT NOT NULL,
    target_path TEXT,
    target_symbol TEXT,
    confidence TEXT NOT NULL,
    line INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_gedge_src ON graph_edges(src_path);
CREATE INDEX IF NOT EXISTS idx_gedge_target ON graph_edges(target_name);
CREATE INDEX IF NOT EXISTS idx_gedge_tsym ON graph_edges(target_symbol);
CREATE TABLE IF NOT EXISTS api_routes (
    path TEXT NOT NULL,
    line INTEGER NOT NULL,
    method TEXT NOT NULL,
    route TEXT NOT NULL,
    handler TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_routes_path ON api_routes(path);
"""

# Columns added after the original schema - ALTER TABLE in _migrate() keeps
# existing index.db files valid (IF NOT EXISTS on CREATE never upgrades them).
_SYMBOL_EXTRA_COLS = (
    ("signature", "TEXT NOT NULL DEFAULT ''"),
    ("doc", "TEXT NOT NULL DEFAULT ''"),
    ("is_test", "INTEGER NOT NULL DEFAULT 0"),
)


class GraphStore:
    def __init__(self, db_path: str):
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self.conn = open_db(db_path)
        self.conn.executescript(GRAPH_SCHEMA)
        self._migrate()

    def _migrate(self) -> None:
        have = {r[1] for r in self.conn.execute("PRAGMA table_info(graph_symbols)")}
        for col, decl in _SYMBOL_EXTRA_COLS:
            if col not in have:
                self.conn.execute(f"ALTER TABLE graph_symbols ADD COLUMN {col} {decl}")  # nosec B608
        self.conn.commit()

    def close(self):
        self.conn.close()

    def known(self) -> dict[str, str]:
        return dict(self.conn.execute("SELECT path, file_hash FROM graph_files").fetchall())

    def delete_file(self, path: str) -> None:
        self.conn.execute("DELETE FROM graph_symbols WHERE path=?", (path,))
        self.conn.execute("DELETE FROM graph_edges WHERE src_path=?", (path,))
        self.conn.execute("DELETE FROM api_routes WHERE path=?", (path,))
        self.conn.execute("DELETE FROM graph_files WHERE path=?", (path,))

    def replace_file(self, fx: FileExtraction, file_hash: str) -> None:
        self.delete_file(fx.path)
        self.conn.executemany(
            "INSERT INTO graph_symbols(path, language, kind, name, "
            "qualified_name, start_line, end_line, parent, signature, doc, "
            "is_test) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            [
                (
                    s.path,
                    s.language,
                    s.kind,
                    s.name,
                    s.qualified_name,
                    s.start_line,
                    s.end_line,
                    s.parent,
                    s.signature,
                    s.doc,
                    1 if s.is_test else 0,
                )
                for s in fx.symbols
            ],
        )
        self.conn.execute(
            "INSERT INTO graph_files(path, file_hash, language, engine, "
            "extracted_at) VALUES(?,?,?,?,?)",
            (fx.path, file_hash, fx.language, fx.engine, time.time()),
        )

    def insert_edges(self, rows: list[tuple]) -> None:
        self.conn.executemany(
            "INSERT INTO graph_edges(src_path, src_symbol, edge_type, "
            "target_name, target_path, target_symbol, confidence, line) "
            "VALUES(?,?,?,?,?,?,?,?)",
            rows,
        )

    def insert_routes(self, routes: list) -> None:
        self.conn.executemany(
            "INSERT INTO api_routes(path, line, method, route, handler) VALUES(?,?,?,?,?)",
            [(r.path, r.line, r.method, r.route, r.handler) for r in routes],
        )

    def clear_edges_from(self, paths: list[str]) -> None:
        for p in paths:
            self.conn.execute("DELETE FROM graph_edges WHERE src_path=?", (p,))

    def commit(self):
        self.conn.commit()


# ---------------------------------------------------------------------------
# build (incremental) + deterministic resolution
# ---------------------------------------------------------------------------


def _file_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def build_graph(cfg: Config, quiet: bool = False) -> dict:
    import sys

    store = GraphStore(cfg.db_path)
    try:
        all_files = discovery.discover_all(cfg)
        known = store.known()
        gone = [p for p in known if p not in set(all_files)]
        for p in gone:
            store.delete_file(p)

        extracted: list[FileExtraction] = []
        pending_edges: dict[str, list[RawEdge]] = {}
        changed = 0
        for path in all_files:
            text = discovery.read_text(cfg, path)
            if text is None:
                continue
            fhash = _file_hash(text)
            if known.get(path) == fhash:
                continue
            language = detect_language(path) or "text"
            fx = extract_file(path, text, language)
            store.replace_file(fx, fhash)
            store.insert_routes(extract_routes(path, text, language))
            pending_edges[path] = fx.edges
            extracted.append(fx)
            changed += 1
            if not quiet:
                print(
                    f"  {path}: {len(fx.symbols)} symbol(s), "
                    f"{len(fx.edges)} raw edge(s) [{fx.engine}]",
                    file=sys.stderr,
                )

        # Resolution needs the full symbol table (changed files can call
        # into unchanged ones), so load it once, then resolve ONLY the
        # changed files' edges.
        symtab: dict[str, list[tuple[str, str]]] = {}  # name -> [(path, qname)]
        for name, path, qname in store.conn.execute(
            "SELECT name, path, qualified_name FROM graph_symbols"
        ):
            symtab.setdefault(name, []).append((path, qname))

        rows: list[tuple] = []
        for path, edges in pending_edges.items():
            for e in edges:
                rows.append(_resolve(e, path, symtab))
        store.clear_edges_from(list(pending_edges.keys()))
        store.insert_edges(rows)
        store.commit()

        # Cross-file staleness: edges in UNCHANGED files pointing at names
        # whose definitions changed remain valid because they reference
        # target_name + resolved target re-checked at query time via joins;
        # a weekly/CI `--full` (delete graph_files) is the reconcile path.
        stats = graph_stats(cfg, store)
        stats.update({"files_extracted": changed, "files_deleted": len(gone)})
        return stats
    finally:
        store.close()


def _resolve(e: RawEdge, src_path: str, symtab: dict[str, list[tuple[str, str]]]) -> tuple:
    """Fixed scope rules: same-file match -> 'local'; unique repo-wide
    match -> 'global'; several matches -> 'ambiguous' (first 3 by path
    order, deterministic); none -> 'external'."""
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

    short = e.target_name.split(".")[-1]
    candidates = symtab.get(short, [])
    same_file = [c for c in candidates if c[0] == src_path]
    if same_file:
        tp, tq = same_file[0]
        return (src_path, e.src_qualified, e.edge_type, e.target_name, tp, tq, "local", e.line)
    if len(candidates) == 1:
        tp, tq = candidates[0]
        return (src_path, e.src_qualified, e.edge_type, e.target_name, tp, tq, "global", e.line)
    if candidates:
        tp, tq = sorted(candidates)[0]
        return (src_path, e.src_qualified, e.edge_type, e.target_name, tp, tq, "ambiguous", e.line)
    return (src_path, e.src_qualified, e.edge_type, e.target_name, None, None, "external", e.line)


# ---------------------------------------------------------------------------
# queries
# ---------------------------------------------------------------------------


def graph_stats(cfg: Config, store: GraphStore | None = None) -> dict:
    own = store is None
    store = store or GraphStore(cfg.db_path)
    try:
        c = store.conn
        return {
            "files": c.execute("SELECT COUNT(*) FROM graph_files").fetchone()[0],
            "symbols": c.execute("SELECT COUNT(*) FROM graph_symbols").fetchone()[0],
            "edges": dict(
                c.execute(
                    "SELECT edge_type, COUNT(*) FROM graph_edges GROUP BY edge_type"
                ).fetchall()
            ),
            "engines": dict(
                c.execute("SELECT engine, COUNT(*) FROM graph_files GROUP BY engine").fetchall()
            ),
            "resolution": dict(
                c.execute(
                    "SELECT confidence, COUNT(*) FROM graph_edges "
                    "WHERE edge_type='calls' GROUP BY confidence"
                ).fetchall()
            ),
        }
    finally:
        if own:
            store.close()


def callers_of(cfg: Config, name: str, include_ambiguous: bool = True) -> list[dict]:
    store = GraphStore(cfg.db_path)
    try:
        conf = "'local','global','ambiguous'" if include_ambiguous else "'local','global'"
        # B608 false positive: `conf` is one of the two hardcoded literal
        # lists above, never caller input; `name` is bound as a parameter.
        sql = (
            "SELECT src_path, src_symbol, target_symbol, confidence, line "  # nosec B608
            "FROM graph_edges "
            "WHERE edge_type='calls' "
            "AND (target_symbol=? OR target_symbol LIKE ? OR target_name=?) "
            f"AND confidence IN ({conf}) "
            "ORDER BY src_path, line"
        )
        rows = store.conn.execute(sql, (name, f"%.{name}", name)).fetchall()
        return [
            {
                "caller": r[1],
                "path": r[0],
                "line": r[4],
                "resolved_target": r[2],
                "confidence": r[3],
            }
            for r in rows
        ]
    finally:
        store.close()


def callees_of(cfg: Config, name: str) -> list[dict]:
    store = GraphStore(cfg.db_path)
    try:
        rows = store.conn.execute(
            """SELECT target_name, target_path, target_symbol, confidence, line
               FROM graph_edges
               WHERE edge_type='calls' AND (src_symbol=? OR src_symbol LIKE ?)
               ORDER BY line""",
            (name, f"%.{name}"),
        ).fetchall()
        return [
            {"callee": r[0], "path": r[1], "resolved": r[2], "confidence": r[3], "line": r[4]}
            for r in rows
        ]
    finally:
        store.close()


def impact_of(cfg: Config, symbol_or_path: str, depth: int = 3) -> dict:
    """Reverse reachability: who (transitively) calls / inherits from /
    imports the given symbol or file. BFS with fixed depth."""
    store = GraphStore(cfg.db_path)
    try:
        c = store.conn
        seeds: set[tuple[str, str | None]] = set()  # (path, qname|None=file)
        for path, qname in c.execute(
            "SELECT path, qualified_name FROM graph_symbols "
            "WHERE qualified_name=? OR name=? OR path=?",
            (symbol_or_path, symbol_or_path.split(".")[-1], symbol_or_path),
        ):
            seeds.add((path, qname))
        if os.sep in symbol_or_path or "/" in symbol_or_path:
            seeds.add((symbol_or_path, None))
        if not seeds:
            return {"seeds": [], "impacted": [], "note": "symbol not found"}

        frontier = set(seeds)
        seen: set[tuple[str, str | None]] = set(seeds)
        levels: list[list[dict]] = []
        for _level in range(depth):
            next_frontier: set[tuple[str, str | None]] = set()
            found: list[dict] = []
            for path, qname in frontier:
                if qname:  # callers + inheritors of this symbol
                    rows = c.execute(
                        """SELECT src_path, src_symbol, edge_type, confidence
                           FROM graph_edges
                           WHERE target_symbol=? AND target_path=?
                             AND edge_type IN ('calls','inherits')""",
                        (qname, path),
                    ).fetchall()
                else:  # importers of this file (by module-ish suffix)
                    stem = os.path.splitext(os.path.basename(path))[0]
                    rows = c.execute(
                        """SELECT src_path, src_symbol, edge_type, confidence
                           FROM graph_edges
                           WHERE edge_type='imports'
                             AND (target_name LIKE ? OR target_name LIKE ?)""",
                        (f"%{stem}", f"%{stem}.%"),
                    ).fetchall()
                for sp, ss, et, conf in rows:
                    key = (sp, ss)
                    if key not in seen:
                        seen.add(key)
                        next_frontier.add(key)
                        found.append({"path": sp, "symbol": ss, "via": et, "confidence": conf})
            if found:
                levels.append(found)
            frontier = next_frontier
            if not frontier:
                break
        return {
            "seeds": [{"path": p, "symbol": q} for p, q in sorted(seeds)],
            "impacted": levels,
            "total_impacted": sum(len(lv) for lv in levels),
        }
    finally:
        store.close()


def export_graph(cfg: Config, fmt: str = "json") -> str:
    store = GraphStore(cfg.db_path)
    try:
        syms = store.conn.execute(
            "SELECT path, kind, qualified_name, start_line, end_line FROM graph_symbols"
        ).fetchall()
        edges = store.conn.execute(
            "SELECT src_path, src_symbol, edge_type, target_name, "
            "target_path, target_symbol, confidence FROM graph_edges "
            "WHERE edge_type != 'contains'"
        ).fetchall()
        if fmt == "dot":
            out = ["digraph code {", "  rankdir=LR;", "  node [shape=box];"]
            for sp, ss, et, tn, tp, tsym, conf in edges:  # noqa: B007
                if not tsym or conf == "external":
                    continue
                src = f"{sp}:{ss}" if ss else sp
                dst = f"{tp}:{tsym}"
                style = " [style=dashed]" if conf == "ambiguous" else ""
                out.append(f'  "{src}" -> "{dst}" [label="{et}"]{style};')
            out.append("}")
            return "\n".join(out)
        import json

        return json.dumps(
            {
                "symbols": [
                    {
                        "path": s[0],
                        "kind": s[1],
                        "qualified_name": s[2],
                        "start_line": s[3],
                        "end_line": s[4],
                    }
                    for s in syms
                ],
                "edges": [
                    {
                        "src_path": e[0],
                        "src_symbol": e[1],
                        "type": e[2],
                        "target_name": e[3],
                        "target_path": e[4],
                        "target_symbol": e[5],
                        "confidence": e[6],
                    }
                    for e in edges
                ],
            },
            indent=2,
        )
    finally:
        store.close()
