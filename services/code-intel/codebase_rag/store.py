"""SQLite index: file state, chunks, embeddings, LLM-context cache.

Embeddings are float32 blobs searched brute-force with numpy cosine
similarity - exact, dependency-free, and comfortably fast up to a few
hundred thousand chunks. (If you outgrow that, this module is the single
seam to swap for pgvector/sqlite-vec.)

Retrieval is hybrid: the pure-cosine `search()` is fused with a SQLite
FTS5 lexical leg (`lexical_search()`) via reciprocal rank fusion in
`hybrid_search()`, then re-ranked in `_rerank()` - by an opt-in
cross-encoder pass (reranker.py; audit item #22) when
CODE_INTEL_RERANK_ENABLED is set and sentence-transformers is installed,
else by a cheap heuristic that boosts hits whose query terms appear in
the enclosing symbol name rather than just the body text. This closes
the "exact function name / error string / config key" recall gap that
pure embedding-cosine search has, without adding any new infrastructure
- it is the same SQLite file, one extra virtual table. FTS5 is optional:
if the local SQLite build lacks it, `fts_available` goes False and
everything transparently falls back to cosine-only search (the previous
behaviour), so this never turns into a hard dependency.

History note (why this file looks the way it does): hybrid FTS5 search
was once implemented twice, independently, in the two vendored copies of
this package. This is the reconciled merge: it keeps the external-content
chunks_fts table synced by SQL triggers (no write-path bookkeeping to
forget; survives any future writer touching `chunks` directly) and ports
over the standalone `lexical_search()` API, the `_rerank()` symbol-boost
pass, the identifier-preserving tokenizer, and the legacy-index backfill
from the other implementation.
"""

from __future__ import annotations

import os
import pathlib
import re
import sqlite3
import time
from dataclasses import dataclass

import numpy as np

from . import reranker

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS files (
    path TEXT PRIMARY KEY,
    file_hash TEXT NOT NULL,
    language TEXT NOT NULL,
    chunk_count INTEGER NOT NULL,
    indexed_at REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    symbol TEXT NOT NULL DEFAULT '',
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    content TEXT NOT NULL,
    context TEXT NOT NULL DEFAULT '',
    hash TEXT NOT NULL,
    embedding BLOB NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_chunks_path ON chunks(path);
CREATE INDEX IF NOT EXISTS idx_chunks_hash ON chunks(hash);
CREATE TABLE IF NOT EXISTS llm_context_cache (
    content_key TEXT PRIMARY KEY,
    summary TEXT NOT NULL,
    created_at REAL NOT NULL
);
"""

# FTS5 virtual table for the lexical leg. External-content (content='chunks')
# so the text is stored once, and kept in sync by SQL triggers rather than
# explicit write-path calls - triggers survive any future code path that
# writes to `chunks`, whereas manual sync silently rots the moment someone
# adds a writer and forgets the FTS bookkeeping. tokenchars '_' keeps
# identifiers like "search_code" or "TENANT_ID" as single tokens instead of
# splitting on underscore, which matters for exact symbol/config-key matches.
FTS_SCHEMA = """
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
    symbol, content, context, content='chunks', content_rowid='id',
    tokenize="unicode61 tokenchars '_'"
);
CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN
    INSERT INTO chunks_fts(rowid, symbol, content, context)
    VALUES (new.id, new.symbol, new.content, new.context);
END;
CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN
    INSERT INTO chunks_fts(chunks_fts, rowid, symbol, content, context)
    VALUES ('delete', old.id, old.symbol, old.content, old.context);
END;
CREATE TRIGGER IF NOT EXISTS chunks_au AFTER UPDATE ON chunks BEGIN
    INSERT INTO chunks_fts(chunks_fts, rowid, symbol, content, context)
    VALUES ('delete', old.id, old.symbol, old.content, old.context);
    INSERT INTO chunks_fts(rowid, symbol, content, context)
    VALUES (new.id, new.symbol, new.content, new.context);
END;
"""

# Bumped whenever the SHAPE of chunks_fts changes (columns, tokenizer,
# external-content vs standalone). On mismatch the table + triggers are
# dropped and rebuilt from `chunks` - this is what migrates both legacy
# layouts that shipped before the reconciliation: the pre-version
# external-content table (default tokenizer, splits identifiers on '_')
# and the standalone chunk_id-addressed table from the tools/ tree (whose
# rowids do NOT match chunk ids, so it must not be queried by this code).
# "2" not "1" so pre-versioning DBs (meta key absent) also rebuild.
FTS_SCHEMA_VERSION = "2"

_TERM_RE = re.compile(r"[A-Za-z0-9_]+")
_MAX_FTS_TERMS = 12


def _fts_terms(text: str) -> list[str]:
    """Extract a bounded, de-duplicated list of identifier-ish tokens from
    free-text/query input, safe to embed in an FTS5 MATCH expression."""
    seen: list[str] = []
    for m in _TERM_RE.finditer(text or ""):
        t = m.group(0)
        if len(t) < 2:
            continue
        if t not in seen:
            seen.append(t)
        if len(seen) >= _MAX_FTS_TERMS:
            break
    return seen


def _heuristic_rerank(hits: list[SearchHit], query_text: str) -> list[SearchHit]:
    """Cheap, deterministic precision pass over an already-fused candidate
    set: boost hits whose query terms appear in the enclosing symbol name
    (a much stronger relevance signal than a term merely occurring
    somewhere in the body), with a smaller boost for a filename match.
    Not a learned/cross-encoder reranker - just a heuristic that is real
    and cheap enough to run on every request. The boost is additive and
    deliberately large relative to RRF fusion scores (~1/60 per leg), so
    an exact-symbol hit reliably surfaces for exact-symbol queries.
    Reorders only; hit.score values are left unchanged.

    This is also the fallback path for `_rerank` below: the cross-encoder
    pass is opt-in (see reranker.py), so any deployment that hasn't
    installed sentence-transformers or set CODE_INTEL_RERANK_ENABLED gets
    exactly this behaviour, unchanged.
    """
    terms = [t.lower() for t in _fts_terms(query_text)]
    if not terms or not hits:
        return hits

    def boosted(hit: SearchHit) -> float:
        symbol_l = hit.symbol.lower()
        path_l = hit.path.lower()
        boost = 0.0
        for t in terms:
            if t in symbol_l:
                boost += 0.15
            if t in path_l:
                boost += 0.05
        return hit.score + boost

    return sorted(hits, key=boosted, reverse=True)


def _rerank(hits: list[SearchHit], query_text: str) -> list[SearchHit]:
    """Audit item #22: real cross-encoder rerank when opted in
    (CODE_INTEL_RERANK_ENABLED=true + sentence-transformers installed -
    see reranker.py for why this stays opt-in rather than a hard
    dependency), falling back to `_heuristic_rerank` otherwise - whether
    that's because it's disabled, the dependency is missing, or inference
    raised (a saturated predict queue, an OOM, whatever). A search request
    degrading to the heuristic ranking is always preferable to a search
    request failing.
    """
    if not query_text or not hits:
        return hits
    if reranker.RERANK_ENABLED:
        try:
            scores = reranker.score(query_text, [h.content for h in hits])
            return [h for _, h in sorted(
                zip(scores, hits, strict=False), key=lambda p: p[0], reverse=True)]
        except Exception:
            pass
    return _heuristic_rerank(hits, query_text)


@dataclass
class SearchHit:
    path: str
    symbol: str
    start_line: int
    end_line: int
    score: float
    content: str
    context: str


def _write_probe(conn: sqlite3.Connection) -> bool:
    """True only if a real write transaction commits - the journal pragma's
    report is NOT proof on network filesystems (a mount can claim WAL and
    then fail the first time -shm or a byte-range lock is touched)."""
    try:
        conn.execute("CREATE TABLE IF NOT EXISTS _journal_probe (x)")
        conn.execute("DROP TABLE _journal_probe")
        conn.commit()
        return True
    except sqlite3.OperationalError:
        return False


def open_db(db_path: str) -> sqlite3.Connection:
    """Connect with the strongest journaling the filesystem can sustain.

    Tier 1 WAL, tier 2 rollback journal, tier 3 `nolock` (no file locking
    at all). The Azure Files/CIFS mount backing prod's data dir refuses
    POSIX locks outright - every write died "database is locked" there
    (2026-09-27). nolock trades sqlite's cross-process write protection for
    functioning storage on such mounts; that is safe here because indexing
    is already serialized per repo in-process (service.py's per-repo lock)
    and the index is a rebuildable cache, not a source of truth.
    """
    conn = sqlite3.connect(db_path)
    try:
        row = conn.execute("PRAGMA journal_mode=WAL").fetchone()
    except sqlite3.OperationalError:
        row = None
    if row and str(row[0]).lower() == "wal" and _write_probe(conn):
        return conn
    conn.close()

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA journal_mode=TRUNCATE")
    if _write_probe(conn):
        return conn
    conn.close()

    # file: URI + nolock=1: the unix VFS skips fcntl entirely. Also forces
    # journal_mode=TRUNCATE so a db previously stamped WAL (a pragma can
    # succeed where -shm writes cannot) is switched back to a mode that
    # needs no locks.
    uri = pathlib.Path(os.path.abspath(db_path)).as_uri() + "?nolock=1"
    conn = sqlite3.connect(uri, uri=True)
    conn.execute("PRAGMA journal_mode=TRUNCATE")
    return conn


class Store:
    def __init__(self, db_path: str):
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self.conn = open_db(db_path)
        self.conn.executescript(SCHEMA)
        # FTS5 may not be compiled into every SQLite build (e.g. some
        # slimmed-down distro packages) - the lexical leg of hybrid_search
        # degrades to pure cosine search in that case rather than failing
        # the whole store open.
        try:
            self._ensure_fts()
            self.fts_available = True
        except sqlite3.OperationalError:
            self.fts_available = False

    def _ensure_fts(self) -> None:
        """Create/migrate the FTS index. Raises sqlite3.OperationalError if
        this SQLite build has no FTS5 (caller degrades to cosine-only)."""
        stamped = self.get_meta("fts_schema_version")
        if stamped != FTS_SCHEMA_VERSION:
            # Unknown or outdated FTS layout: drop and rebuild. Cheap for
            # fresh DBs (nothing to drop, nothing to backfill); a one-time
            # full re-tokenize for legacy indexes.
            self.conn.executescript(
                "DROP TRIGGER IF EXISTS chunks_ai;"
                "DROP TRIGGER IF EXISTS chunks_ad;"
                "DROP TRIGGER IF EXISTS chunks_au;"
                "DROP TABLE IF EXISTS chunks_fts;")
        self.conn.executescript(FTS_SCHEMA)
        if stamped != FTS_SCHEMA_VERSION:
            self._backfill_fts_if_needed()
            # Stamp AFTER the backfill so a crash in between self-heals on
            # the next open (version still mismatched -> rebuild again).
            self.set_meta("fts_schema_version", FTS_SCHEMA_VERSION)
            self.conn.commit()

    def _backfill_fts_if_needed(self) -> None:
        """Backfill after _ensure_fts (re)creates the FTS table, so indexes
        built before FTS support - or by a different FTS layout - get
        lexical coverage immediately, without a full re-index. Genuinely
        needed even with triggers: triggers only cover writes made AFTER the
        table exists, so a DB indexed by pre-FTS code has chunks but an
        empty FTS index. Uses FTS5's 'rebuild' command, the idiomatic full
        re-sync for external-content tables.

        "If needed" is decided by the caller's schema-version stamp, NOT by
        counting chunks_fts rows: on an external-content table a plain
        SELECT COUNT(*) is answered from the content table (chunks), so it
        can never observe that the fts index itself is empty - an
        empty-index probe would have to poke fts5 shadow tables, which this
        code deliberately does not depend on.
        """
        chunk_count = self.conn.execute(
            "SELECT COUNT(*) FROM chunks").fetchone()[0]
        if chunk_count > 0:
            self.conn.execute(
                "INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")

    def close(self) -> None:
        self.conn.close()

    # -- meta ---------------------------------------------------------------

    def get_meta(self, key: str) -> str | None:
        row = self.conn.execute(
            "SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_meta(self, key: str, value: str) -> None:
        self.conn.execute(
            "INSERT INTO meta(key, value) VALUES(?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value))

    # -- files --------------------------------------------------------------

    def known_files(self) -> dict[str, str]:
        """path -> file_hash for every indexed file."""
        return dict(self.conn.execute(
            "SELECT path, file_hash FROM files").fetchall())

    def delete_file(self, path: str) -> int:
        # No FTS bookkeeping here on purpose: the chunks_ad trigger keeps
        # chunks_fts in sync with this DELETE.
        cur = self.conn.execute("DELETE FROM chunks WHERE path=?", (path,))
        self.conn.execute("DELETE FROM files WHERE path=?", (path,))
        return cur.rowcount

    # -- chunks -------------------------------------------------------------

    def chunk_embeddings_for_file(self, path: str) -> dict[str, bytes]:
        """hash -> embedding blob for one file's current chunks."""
        return dict(self.conn.execute(
            "SELECT hash, embedding FROM chunks WHERE path=?", (path,)
        ).fetchall())

    def lookup_embeddings_by_hash(self, hashes: list[str]) -> dict[str, bytes]:
        """Global reuse: a chunk moved between files keeps its embedding."""
        out: dict[str, bytes] = {}
        CHUNK = 500
        for i in range(0, len(hashes), CHUNK):
            batch = hashes[i:i + CHUNK]
            marks = ",".join("?" * len(batch))
            # B608 false positive: only "?" placeholders are interpolated;
            # every hash value is bound as a parameter.
            sql = f"SELECT hash, embedding FROM chunks WHERE hash IN ({marks})"  # nosec B608
            for h, blob in self.conn.execute(sql, batch):
                out.setdefault(h, blob)
        return out

    def replace_file_chunks(self, path: str, file_hash: str, language: str,
                            rows: list[tuple]) -> None:
        """rows: (chunk_index, symbol, start_line, end_line, content,
        context, hash, embedding_blob)

        No FTS bookkeeping here on purpose: the chunks_ad/chunks_ai triggers
        mirror the DELETE and INSERTs into chunks_fts automatically."""
        self.conn.execute("DELETE FROM chunks WHERE path=?", (path,))
        self.conn.executemany(
            "INSERT INTO chunks(path, chunk_index, symbol, start_line, "
            "end_line, content, context, hash, embedding) "
            "VALUES(?,?,?,?,?,?,?,?,?)",
            [(path, *row) for row in rows])
        self.conn.execute(
            "INSERT INTO files(path, file_hash, language, chunk_count, indexed_at) "
            "VALUES(?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET "
            "file_hash=excluded.file_hash, language=excluded.language, "
            "chunk_count=excluded.chunk_count, indexed_at=excluded.indexed_at",
            (path, file_hash, language, len(rows), time.time()))

    # -- llm context cache ----------------------------------------------------

    def get_llm_summaries(self, keys: list[str]) -> dict[str, str]:
        out: dict[str, str] = {}
        CHUNK = 500
        for i in range(0, len(keys), CHUNK):
            batch = keys[i:i + CHUNK]
            marks = ",".join("?" * len(batch))
            # B608 false positive: only "?" placeholders are interpolated;
            # every key is bound as a parameter.
            sql = ("SELECT content_key, summary FROM llm_context_cache "  # nosec B608
                   f"WHERE content_key IN ({marks})")
            out.update(self.conn.execute(sql, batch).fetchall())
        return out

    def put_llm_summary(self, key: str, summary: str) -> None:
        self.conn.execute(
            "INSERT OR REPLACE INTO llm_context_cache(content_key, summary, created_at) "
            "VALUES(?,?,?)", (key, summary, time.time()))

    # -- stats / search -------------------------------------------------------

    def stats(self) -> dict:
        files = self.conn.execute("SELECT COUNT(*) FROM files").fetchone()[0]
        chunks = self.conn.execute("SELECT COUNT(*) FROM chunks").fetchone()[0]
        langs = self.conn.execute(
            "SELECT language, COUNT(*) FROM files GROUP BY language "
            "ORDER BY COUNT(*) DESC").fetchall()
        return {
            "files": files, "chunks": chunks, "languages": langs,
            "last_commit": self.get_meta("last_commit"),
            "embedder": self.get_meta("embedder_identity"),
            "last_indexed": self.get_meta("last_indexed_at"),
            "lexical_search_enabled": self.fts_available,
        }

    def search(self, query_vec: np.ndarray, top_k: int = 8,
               path_prefix: str | None = None) -> list[SearchHit]:
        """Pure embedding-cosine search (unchanged behaviour - still used
        directly by callers that only want the semantic leg)."""
        sql = ("SELECT path, symbol, start_line, end_line, content, context, "
               "embedding FROM chunks")
        params: tuple = ()
        if path_prefix:
            sql += " WHERE path LIKE ?"
            params = (path_prefix + "%",)
        rows = self.conn.execute(sql, params).fetchall()
        if not rows:
            return []
        mat = np.frombuffer(
            b"".join(r[6] for r in rows), dtype=np.float32
        ).reshape(len(rows), -1)
        q = query_vec.astype(np.float32).ravel()
        denom = (np.linalg.norm(mat, axis=1) * (np.linalg.norm(q) + 1e-9)) + 1e-9
        scores = mat @ q / denom
        order = np.argsort(-scores)[:top_k]
        return [
            SearchHit(rows[i][0], rows[i][1], rows[i][2], rows[i][3],
                      float(scores[i]), rows[i][4], rows[i][5])
            for i in order
        ]

    def lexical_search(self, query_text: str, top_k: int = 8,
                       path_prefix: str | None = None) -> list[SearchHit]:
        """Full-text search over symbol/content/context via FTS5 + bm25
        ranking. Returns [] (never raises) if FTS5 is unavailable or the
        query has no usable terms, so callers can always fall back to
        semantic-only results."""
        if not self.fts_available:
            return []
        terms = _fts_terms(query_text)
        if not terms:
            return []
        match_expr = " OR ".join(f'"{t}"' for t in terms)
        sql = (
            "SELECT c.path, c.symbol, c.start_line, c.end_line, c.content, "
            "c.context, bm25(chunks_fts) AS rank "
            "FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid "
            "WHERE chunks_fts MATCH ?"
        )
        params: list = [match_expr]
        if path_prefix:
            sql += " AND c.path LIKE ?"
            params.append(path_prefix + "%")
        sql += " ORDER BY rank LIMIT ?"
        params.append(top_k)
        try:
            rows = self.conn.execute(sql, params).fetchall()
        except sqlite3.OperationalError:
            return []
        # bm25() is "lower/more negative = more relevant" in SQLite FTS5;
        # negate so SearchHit.score stays "higher = more relevant" like the
        # cosine leg (callers/tests can then treat score uniformly).
        return [
            SearchHit(r[0], r[1], r[2], r[3], float(-r[6]), r[4], r[5])
            for r in rows
        ]

    def hybrid_search(self, query_vec: np.ndarray, query_text: str,
                      top_k: int = 8, path_prefix: str | None = None,
                      candidate_k: int | None = None,
                      rrf_k: int = 60) -> list[SearchHit]:
        """Fuse the semantic (cosine) and lexical (FTS5/bm25) result sets
        with reciprocal rank fusion, then apply the cheap symbol-match
        reranker. This is the retrieval path `search_code` / `/search`
        should use: it recovers exact-symbol/error-string/config-key hits
        that pure cosine search misses, while keeping cosine's strength on
        natural-language queries. Falls back to pure cosine transparently
        when FTS5 is unavailable or the query yields no lexical hits (the
        symbol-boost rerank still applies - it needs no FTS).

        RRF is used (rather than a weighted score sum) because bm25 and
        cosine scores live on incomparable scales; RRF only needs each
        list's *rank order*, which sidesteps that entirely and needs no
        tuning beyond the standard rrf_k=60 constant.
        """
        candidate_k = candidate_k or max(top_k * 4, 20)
        semantic = self.search(query_vec, top_k=candidate_k,
                               path_prefix=path_prefix)
        lexical = self.lexical_search(query_text, top_k=candidate_k,
                                      path_prefix=path_prefix)
        if not lexical:
            fused = semantic
        elif not semantic:
            fused = lexical
        else:
            fused_scores: dict[tuple, float] = {}
            hit_by_key: dict[tuple, SearchHit] = {}
            for rank, hit in enumerate(semantic):
                key = (hit.path, hit.start_line, hit.end_line)
                fused_scores[key] = fused_scores.get(key, 0.0) + 1.0 / (rrf_k + rank + 1)
                hit_by_key[key] = hit
            for rank, hit in enumerate(lexical):
                key = (hit.path, hit.start_line, hit.end_line)
                fused_scores[key] = fused_scores.get(key, 0.0) + 1.0 / (rrf_k + rank + 1)
                hit_by_key.setdefault(key, hit)
            fused = [
                SearchHit(h.path, h.symbol, h.start_line, h.end_line,
                          fused_scores[key], h.content, h.context)
                for key, h in sorted(
                    hit_by_key.items(), key=lambda kv: fused_scores[kv[0]],
                    reverse=True)
            ]
        return _rerank(fused, query_text)[:top_k]

    def commit(self) -> None:
        self.conn.commit()
