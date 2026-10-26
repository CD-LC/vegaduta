"""Higher-level read queries over the code graph (see graph.py's javadoc for
extraction/resolution guarantees). Everything here is deterministic SQLite +
stdlib - the same "an answer exists only if the graph produced it" contract.
No LLM anywhere on these paths.

Endpoints these power (app/main.py):

- repo_map        - PageRank-ranked skeleton under a char budget (agent
                    context: the repo's shape without reading files)
- find_symbols    - name-path symbol search (kind/path filters)
- symbol_detail   - one symbol's metadata + exact source body from disk
- file_outline    - one file's symbol tree
- references_of   - every inbound edge (calls + inherits + references)
- implementations - recursive subclass/implementer tree
- call_path       - BFS shortest path between two symbols over `calls`
- dead_code       - symbols with zero inbound non-contains edges
- tests_for       - test-file symbols that reference a symbol
- api_surface     - extracted HTTP routes (routes_extract.py)
- hotspots        - git churn x size ranking (gitstate.py)
- import_cycles   - file-level import-graph strongly connected components
"""

from __future__ import annotations

import os
import re
import subprocess
from collections import defaultdict, deque

from .config import Config
from .gitstate import is_git_repo
from .graph import GraphStore

_CONFS = "'local','global','ambiguous'"


def _store(cfg: Config) -> GraphStore:
    return GraphStore(cfg.db_path)


def _read_lines(cfg: Config, path: str) -> list[str] | None:
    """Repo-relative file read, confined to cfg.root (paths in graph tables
    are stored relative, but validate anyway - SEC-016)."""
    p = os.path.normpath(os.path.join(cfg.root, path))
    if not p.startswith(os.path.abspath(cfg.root) + os.sep):
        return None
    try:
        with open(p, encoding="utf-8", errors="replace") as f:
            return f.read().split("\n")
    except OSError:
        return None


# ---------------------------------------------------------------------------
# symbol navigation
# ---------------------------------------------------------------------------


def find_symbols(
    cfg: Config,
    query: str = "",
    kind: str = "",
    path_prefix: str = "",
    limit: int = 50,
    include_tests: bool = True,
) -> list[dict]:
    """Name-path search: `query` is a substring match against name OR the tail
    of qualified_name (`Auth.login` finds `auth.Auth.login`)."""
    store = _store(cfg)
    try:
        sql = (
            "SELECT path, kind, name, qualified_name, start_line, end_line,"
            " signature, doc, is_test FROM graph_symbols WHERE 1=1"
        )
        args: list = []
        if query:
            sql += " AND (name LIKE ? OR qualified_name LIKE ?)"
            args += [f"%{query}%", f"%{query}%"]
        if kind:
            sql += " AND kind=?"
            args.append(kind)
        if path_prefix:
            sql += " AND path LIKE ?"
            args.append(f"{path_prefix}%")
        if not include_tests:
            sql += " AND is_test=0"
        sql += " ORDER BY path, start_line LIMIT ?"
        args.append(max(1, min(limit, 200)))
        return [
            {
                "path": r[0],
                "kind": r[1],
                "name": r[2],
                "qualified_name": r[3],
                "lines": [r[4], r[5]],
                "signature": r[6],
                "doc": r[7],
                "is_test": bool(r[8]),
            }
            for r in store.conn.execute(sql, args)
        ]
    finally:
        store.close()


def symbol_detail(cfg: Config, qualified_name: str, include_body: bool = True) -> dict | None:
    """One symbol's full record + its exact source span read back from disk.
    `include_body=False` is the cheap variant for agents that only need the
    span to cite."""
    store = _store(cfg)
    try:
        row = store.conn.execute(
            "SELECT path, kind, name, qualified_name, start_line, end_line,"
            " signature, doc, is_test FROM graph_symbols WHERE qualified_name=?"
            " OR name=? ORDER BY is_test, path LIMIT 1",
            (qualified_name, qualified_name.split(".")[-1]),
        ).fetchone()
        if not row:
            return None
        out = {
            "path": row[0],
            "kind": row[1],
            "name": row[2],
            "qualified_name": row[3],
            "lines": [row[4], row[5]],
            "signature": row[6],
            "doc": row[7],
            "is_test": bool(row[8]),
        }
        if include_body:
            lines = _read_lines(cfg, row[0])
            if lines is not None:
                body = "\n".join(lines[row[4] - 1 : row[5]])
                out["body"] = body[:20000]
                if len(body) > 20000:
                    out["body_truncated"] = True
        return out
    finally:
        store.close()


def file_outline(cfg: Config, path: str) -> list[dict]:
    """One file's symbol tree, top-down - Serena's get_symbols_overview."""
    return find_symbols(cfg, path_prefix=path, limit=200)


def references_of(cfg: Config, name: str, include_ambiguous: bool = True) -> dict:
    """Every inbound edge mentioning `name`: calls, inherits and value/type
    `references` edges - the union callers_of and callees can't answer
    (`find_referencing_symbols`)."""
    store = _store(cfg)
    try:
        conf = _CONFS if include_ambiguous else "'local','global'"
        sql = (
            "SELECT src_path, src_symbol, edge_type, confidence, line "
            "FROM graph_edges WHERE edge_type IN"
            " ('calls','inherits','references') "
            "AND (target_symbol=? OR target_symbol LIKE ? OR target_name=?) "
            f"AND confidence IN ({conf}) "  # nosec B608 - literal list above
            "ORDER BY edge_type, src_path, line LIMIT 500"
        )
        rows = store.conn.execute(sql, (name, f"%.{name}", name)).fetchall()
        grouped: dict[str, list[dict]] = defaultdict(list)
        for sp, ss, et, cf, ln in rows:
            grouped[et].append({"path": sp, "symbol": ss, "line": ln, "confidence": cf})
        return {
            "symbol": name,
            "total": sum(len(v) for v in grouped.values()),
            "by_type": dict(grouped),
        }
    finally:
        store.close()


def implementations_of(cfg: Config, name: str, depth: int = 4) -> dict:
    """Recursive `inherits` walk - subclasses/implementers of a class or
    interface, up to `depth` levels."""
    store = _store(cfg)
    try:
        seeds = {
            r[0]
            for r in store.conn.execute(
                "SELECT qualified_name FROM graph_symbols WHERE qualified_name=? OR name=?",
                (name, name.split(".")[-1]),
            )
        }
        if not seeds:
            return {"symbol": name, "implementations": [], "note": "symbol not found"}
        seen = set(seeds)
        frontier = set(seeds)
        levels: list[list[dict]] = []
        for _ in range(depth):
            found: list[dict] = []
            nxt: set[str] = set()
            for seed in frontier:
                rows = store.conn.execute(
                    "SELECT DISTINCT src_symbol, src_path, confidence "
                    "FROM graph_edges WHERE edge_type='inherits'"
                    " AND (target_symbol=? OR target_name=?) AND src_symbol"
                    " IS NOT NULL",
                    (seed, seed.split(".")[-1]),
                ).fetchall()
                for ss, sp, cf in rows:
                    if ss and ss not in seen:
                        seen.add(ss)
                        nxt.add(ss)
                        found.append({"symbol": ss, "path": sp, "confidence": cf})
            if found:
                levels.append(found)
            frontier = nxt
            if not frontier:
                break
        return {"symbol": name, "implementations": levels, "total": sum(len(lv) for lv in levels)}
    finally:
        store.close()


def call_path(cfg: Config, src: str, dst: str, max_depth: int = 8) -> dict:
    """BFS shortest path over `calls` edges between two symbols - "how does
    X end up calling Y". Returns the chain, not just reachability."""
    store = _store(cfg)
    try:
        start = [
            r[0]
            for r in store.conn.execute(
                "SELECT DISTINCT src_symbol FROM graph_edges WHERE "
                "src_symbol=? OR src_symbol LIKE ?",
                (src, f"%.{src}"),
            )
        ] or [src]
        goal = {
            r[0]
            for r in store.conn.execute(
                "SELECT target_symbol FROM graph_edges WHERE target_symbol=?"
                " OR target_symbol LIKE ? OR target_name=?",
                (dst, f"%.{dst}", dst),
            )
        }
        goal.update({dst})
        visited: dict[str, str | None] = dict.fromkeys(start)
        queue: deque[str] = deque(start)
        hit: str | None = None
        steps = 0
        while queue and steps < 50000:
            steps += 1
            node = queue.popleft()
            depth = 0
            p = node
            while visited.get(p) is not None:
                depth += 1
                p = visited[p]  # type: ignore[index]
            if depth > max_depth:
                continue
            rows = store.conn.execute(
                "SELECT DISTINCT target_symbol FROM graph_edges WHERE"
                " edge_type='calls' AND src_symbol=? AND target_symbol"
                " IS NOT NULL",
                (node,),
            ).fetchall()
            for (t,) in rows:
                if t in visited:
                    continue
                visited[t] = node
                if t in goal:
                    hit = t
                    queue.clear()
                    break
                queue.append(t)
        if hit is None:
            return {
                "found": False,
                "from": src,
                "to": dst,
                "note": "no call path within depth bound",
            }
        chain = [hit]
        while visited[chain[-1]] is not None:
            chain.append(visited[chain[-1]])  # type: ignore[arg-type]
        chain.reverse()
        return {"found": True, "from": src, "to": dst, "depth": len(chain) - 1, "path": chain}
    finally:
        store.close()


def dead_code(cfg: Config, limit: int = 100, include_tests: bool = False) -> dict:
    """Symbols with zero inbound calls/inherits/references edges - candidate
    unreachable code. Entrypoint-shaped names (main, __init__, __call__,
    handler-ish, exports) are excluded; this is a REVIEW LIST, not proof."""
    store = _store(cfg)
    try:
        entry_rx = re.compile(
            r"^(main|__init__|__call__|__new__|__main__|init|setup|teardown|"
            r"setUp|tearDown|run|start|stop|handler|middleware|factory|"
            r"configure|register|plugin|hook|cli|app|main_?)$",
            re.I,
        )
        sql = (
            "SELECT s.qualified_name, s.path, s.kind, s.signature, s.is_test,"
            " s.start_line FROM graph_symbols s "
            "WHERE s.kind IN ('function','method','class','interface','struct')"
            f"{' AND s.is_test=0' if not include_tests else ''}"
            " AND NOT EXISTS (SELECT 1 FROM graph_edges e WHERE"
            "   e.target_symbol = s.qualified_name AND e.edge_type IN"
            "   ('calls','inherits','references'))"
            " AND s.parent IS NULL"  # top-level only - members show via owners
            " ORDER BY s.path LIMIT ?"
        )
        rows = store.conn.execute(sql, (max(1, min(limit, 500)),)).fetchall()
        cands = [
            {
                "symbol": r[0],
                "path": r[1],
                "kind": r[2],
                "signature": r[3],
                "is_test": bool(r[4]),
                "line": r[5],
            }
            for r in rows
            if not entry_rx.match(r[0].split(".")[-1])
        ]
        return {
            "candidates": cands,
            "count": len(cands),
            "note": "top-level symbols with zero inbound call/inherit/"
            "reference edges - candidates for review, not proof",
        }
    finally:
        store.close()


def tests_for(cfg: Config, name: str) -> dict:
    """Test-side linkage: which TEST-file symbols call/reference `name`, and
    which symbols `name` is covered by (via inbound edges from is_test files)."""
    store = _store(cfg)
    try:
        rows = store.conn.execute(
            "SELECT e.src_path, e.src_symbol, e.edge_type, e.line "
            "FROM graph_edges e JOIN graph_symbols s ON"
            " s.qualified_name = e.src_symbol "
            "WHERE s.is_test=1 AND (e.target_symbol=? OR e.target_symbol LIKE ?"
            " OR e.target_name=?) AND e.edge_type IN"
            " ('calls','inherits','references') LIMIT 200",
            (name, f"%.{name}", name),
        ).fetchall()
        files = sorted({r[0] for r in rows})
        return {
            "symbol": name,
            "test_callers": [
                {"path": r[0], "symbol": r[1], "via": r[2], "line": r[3]} for r in rows
            ],
            "test_files": files,
            "covered": bool(rows),
            "note": "absent rows mean the symbol has no recorded "
            "test-side inbound edge - not proof of zero coverage",
        }
    finally:
        store.close()


# ---------------------------------------------------------------------------
# repo-scale views
# ---------------------------------------------------------------------------


def api_surface(cfg: Config) -> dict:
    """Extracted HTTP routes (routes_extract.py at build time) grouped by
    method - the service's documented external surface."""
    store = _store(cfg)
    try:
        rows = store.conn.execute(
            "SELECT path, line, method, route, handler FROM api_routes ORDER BY route, path"
        ).fetchall()
        by_method: dict[str, int] = defaultdict(int)
        routes = []
        for path, line, method, route, handler in rows:
            by_method[method] += 1
            routes.append(
                {"method": method, "route": route, "file": path, "line": line, "handler": handler}
            )
        frameworks = {
            r[0]
            for r in store.conn.execute(
                "SELECT DISTINCT language FROM graph_files WHERE language IN"
                " ('python','javascript','typescript','tsx','java','kotlin','go',"
                "'rust','csharp')"
            )
        }
        return {
            "routes": routes,
            "count": len(routes),
            "by_method": dict(by_method),
            "coverage": {
                "framework_languages_present": sorted(frameworks),
                "note": "decorator/registration-shaped routes only - "
                "dynamic routers and computed paths are not "
                "visible to this scan",
            },
        }
    finally:
        store.close()


def repo_map(cfg: Config, budget: int = 16000, top_n: int = 200) -> dict:
    """PageRank-ranked skeleton: the repo's most-central symbols with their
    signatures (never bodies), grouped by file, clipped to a CHAR budget -
    aider-style. Agents call this FIRST to learn the codebase's shape before
    fetching anything else."""
    store = _store(cfg)
    try:
        # PageRank over calls+inherits+references edges (symbol granularity).
        edges: dict[str, set[str]] = defaultdict(set)
        for ss, ts in store.conn.execute(
            "SELECT src_symbol, target_symbol FROM graph_edges WHERE"
            " edge_type IN ('calls','inherits','references') AND"
            " src_symbol IS NOT NULL AND target_symbol IS NOT NULL"
        ):
            edges[ss].add(ts)
        nodes = set(edges) | {t for v in edges.values() for t in v}
        n = len(nodes) or 1
        rank = dict.fromkeys(nodes, 1.0 / n)
        for _ in range(12):  # fixed iterations, deterministic
            nxt = dict.fromkeys(nodes, 0.15 / n)
            for s, ts in edges.items():
                share = rank[s] / len(ts)
                for t in ts:
                    nxt[t] += 0.85 * share
            rank = nxt
        ranked = sorted(nodes, key=lambda k: (-rank[k], k))[:top_n]
        if ranked:
            rows = store.conn.execute(
                "SELECT qualified_name, path, kind, signature, doc,"
                " start_line, is_test FROM graph_symbols WHERE"
                f" qualified_name IN ({','.join('?' * len(ranked))})",  # nosec B608
                ranked,
            ).fetchall()
        else:
            rows = []
        meta = {r[0]: r for r in rows}
        # Emit ranked sections per file until the budget runs out.
        sections: dict[str, list[dict]] = defaultdict(list)
        used = 0
        for qn in ranked:
            r = meta.get(qn)
            if not r:
                continue
            entry = {
                "symbol": qn,
                "kind": r[2],
                "signature": r[3],
                "doc": r[4][:120],
                "line": r[5],
                "rank": round(rank[qn], 6),
            }
            cost = len(qn) + len(r[3]) + 12
            if used + cost > budget:
                break
            used += cost
            sections[r[1]].append(entry)
        return {
            "budget_chars": budget,
            "used_chars": used,
            "symbols_shown": sum(len(v) for v in sections.values()),
            "files": [
                {"path": p, "symbols": syms}
                for p, syms in sorted(sections.items(), key=lambda kv: -len(kv[1]))
            ],
            "note": "top symbols by PageRank over call/inherit/reference "
            "edges - centrality, not importance; use find_symbols/"
            "symbol_detail to go deeper",
        }
    finally:
        store.close()


def import_cycles(cfg: Config) -> dict:
    """File-level strongly connected components over `imports` edges -
    architectural smell: modules that mutually depend. Tarjan's algorithm,
    iterative."""
    store = _store(cfg)
    try:
        adj: dict[str, set[str]] = defaultdict(set)
        for src, tgt in store.conn.execute(
            "SELECT src_path, target_path FROM graph_edges WHERE"
            " edge_type='imports' AND target_path IS NOT NULL"
        ):
            adj[src].add(tgt)
        # Tarjan (iterative, explicit stack - recursive blows up on deep graphs)
        index: dict[str, int] = {}
        low: dict[str, int] = {}
        on_stack: set[str] = set()
        stack: list[str] = []
        sccs: list[list[str]] = []
        counter = [0]
        for root in list(adj) + [t for v in adj.values() for t in v]:
            if root in index:
                continue
            work = [(root, iter(sorted(adj.get(root, ()))))]
            index[root] = low[root] = counter[0]
            counter[0] += 1
            stack.append(root)
            on_stack.add(root)
            while work:
                node, it = work[-1]
                nxt = next(it, None)
                if nxt is None:
                    work.pop()
                    if low[node] == index[node]:
                        scc = []
                        while True:
                            w = stack.pop()
                            on_stack.discard(w)
                            scc.append(w)
                            if w == node:
                                break
                        if len(scc) > 1:
                            sccs.append(sorted(scc))
                    if work:
                        parent = work[-1][0]
                        low[parent] = min(low[parent], low[node])
                    continue
                if nxt not in index:
                    index[nxt] = low[nxt] = counter[0]
                    counter[0] += 1
                    stack.append(nxt)
                    on_stack.add(nxt)
                    work.append((nxt, iter(sorted(adj.get(nxt, ())))))
                elif nxt in on_stack:
                    low[node] = min(low[node], index[nxt])
        return {
            "cycles": sccs,
            "count": len(sccs),
            "note": "file-level import cycles (SCCs of size > 1) - "
            "each is a tangle where removing one file breaks "
            "the cycle",
        }
    finally:
        store.close()


def hotspots(cfg: Config, days: int = 90, limit: int = 30) -> dict:
    """git churn x size: files changed most often AND carrying the most
    symbols - the places bugs concentrate. Non-git repos return an honest
    empty result."""
    if not is_git_repo(cfg.root):
        return {"hotspots": [], "note": "not a git checkout - no churn data"}
    try:
        out = subprocess.run(
            [
                "git",
                "-c",
                f"safe.directory={os.path.realpath(cfg.root)}",
                "-C",
                cfg.root,
                "log",
                f"--since={int(days)} days ago",
                "--numstat",
                "--format=",
                "-z",
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if out.returncode != 0:
            return {"hotspots": [], "note": "git log failed", "error": out.stderr.strip()[:200]}
    except Exception as e:
        return {"hotspots": [], "note": "git unavailable", "error": str(e)[:200]}
    adds: dict[str, int] = defaultdict(int)
    dels: dict[str, int] = defaultdict(int)
    touches: dict[str, int] = defaultdict(int)
    for part in out.stdout.split("\0"):
        fields = part.split("\t")
        if len(fields) == 3 and fields[0].isdigit():
            a, d, p = fields
            adds[p] += int(a)
            dels[p] += int(d)
            touches[p] += 1
    store = _store(cfg)
    try:
        sym_counts = dict(
            store.conn.execute("SELECT path, COUNT(*) FROM graph_symbols GROUP BY path")
        )
    finally:
        store.close()
    rows = []
    for p, t in touches.items():
        churn = adds.get(p, 0) + dels.get(p, 0)
        rows.append(
            {
                "path": p,
                "changes": t,
                "churn_lines": churn,
                "symbols": sym_counts.get(p, 0),
                "score": round(t * max(churn, 1), 1),
            }
        )
    rows.sort(key=lambda r: (-r["score"], r["path"]))
    return {
        "window_days": days,
        "hotspots": rows[: max(1, min(limit, 200))],
        "note": "changes x churned-lines - review attention and test coverage belong here first",
    }
