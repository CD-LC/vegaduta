"""Framework-free tests for the reconciled hybrid FTS5 store
(codebase_rag/store.py): trigger-synced FTS rows, legacy-index backfill,
tools-tree standalone-table migration, lexical_search leg, symbol-boost
rerank, and the fts-unavailable degradation path.

Uses the deterministic HashEmbedder (no model download, no ML deps) -
retrieval QUALITY is not asserted anywhere, only mechanics: which rows
exist, which legs ran, how boosts reorder a candidate set.

Run: python3 -m tests.test_store_search   (from the code-intel directory)
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np

from codebase_rag import reranker as reranker_mod
from codebase_rag.embedders import HashEmbedder
from codebase_rag.store import (
    FTS_SCHEMA_VERSION,
    SCHEMA,
    SearchHit,
    Store,
    _fts_terms,
    _heuristic_rerank,
    _rerank,
)

PASS = 0
FAIL = 0

EMB = HashEmbedder()


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def blob(text: str) -> bytes:
    return EMB.embed([text])[0].tobytes()


def row(symbol: str, content: str, idx: int = 0, start: int = 1,
        end: int = 10, context: str = "") -> tuple:
    """(chunk_index, symbol, start_line, end_line, content, context,
    hash, embedding) - the replace_file_chunks row shape."""
    return (idx, symbol, start, end, content, context,
            f"h-{symbol}-{idx}", blob(content))


def fts_count(store: Store) -> int:
    """Rows actually present in the FTS index. On an external-content
    table `SELECT COUNT(*) FROM chunks_fts` is answered from the content
    table (chunks) and would pass vacuously, so probe the docsize shadow
    table - one row per indexed document (present because the table is
    created with default columnsize=1)."""
    return store.conn.execute(
        "SELECT COUNT(*) FROM chunks_fts_docsize").fetchone()[0]


def db(tmp: str, name: str) -> str:
    return os.path.join(tmp, name, "index.db")


def test_trigger_sync(tmp: str):
    print("== trigger-synced FTS rows (insert / update / delete) ==")
    s = Store(db(tmp, "trig"))
    check("fts available in this build (rest of suite assumes it)",
          s.fts_available)
    s.replace_file_chunks("a.py", "fh1", "python", [
        row("alpha_fn", "def alpha_fn():\n    return legacy_marker", idx=0),
        row("beta_fn", "def beta_fn():\n    pass", idx=1),
    ])
    s.commit()
    check("insert: fts row per chunk", fts_count(s) == 2,
          f"got {fts_count(s)}")
    check("insert: lexical finds new symbol",
          any(h.symbol == "alpha_fn"
              for h in s.lexical_search("legacy_marker")))

    # Update path: replace_file_chunks deletes + reinserts; the chunks_ad/
    # chunks_ai triggers must keep fts in step (old terms gone, new present).
    s.replace_file_chunks("a.py", "fh2", "python", [
        row("alpha_fn", "def alpha_fn():\n    return fresh_marker", idx=0),
    ])
    s.commit()
    check("update: fts row count follows chunk count", fts_count(s) == 1,
          f"got {fts_count(s)}")
    check("update: stale term no longer matches",
          s.lexical_search("legacy_marker") == [])
    check("update: new term matches",
          len(s.lexical_search("fresh_marker")) == 1)

    s.delete_file("a.py")
    s.commit()
    check("delete: fts emptied by trigger", fts_count(s) == 0,
          f"got {fts_count(s)}")
    check("delete: lexical returns nothing",
          s.lexical_search("fresh_marker") == [])
    s.close()


def test_backfill_legacy(tmp: str):
    print("== backfill: index built by pre-FTS code ==")
    # Simulate an index written by the old code: chunks exist, but no
    # chunks_fts table, no triggers, no fts_schema_version meta key.
    path = db(tmp, "legacy")
    os.makedirs(os.path.dirname(path))
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    conn.execute(
        "INSERT INTO chunks(path, chunk_index, symbol, start_line, end_line, "
        "content, context, hash, embedding) VALUES(?,?,?,?,?,?,?,?,?)",
        ("old.py", 0, "old_fn", 1, 5, "def old_fn(): pre_fts_token", "",
         "h-old", blob("def old_fn(): pre_fts_token")))
    conn.commit()
    conn.close()

    s = Store(path)  # triggers alone would leave this chunk invisible to FTS
    check("backfill: fts row created for pre-existing chunk",
          fts_count(s) == 1, f"got {fts_count(s)}")
    check("backfill: legacy chunk lexically searchable",
          any(h.symbol == "old_fn" for h in s.lexical_search("pre_fts_token")))
    check("backfill: schema version stamped",
          s.get_meta("fts_schema_version") == FTS_SCHEMA_VERSION)
    s.close()

    # Reopen: version matches, backfill must not double anything.
    s = Store(path)
    check("reopen: no duplicate fts rows", fts_count(s) == 1,
          f"got {fts_count(s)}")
    s.close()


def test_migrate_standalone_table(tmp: str):
    print("== migration: tools-tree standalone chunk_id fts table ==")
    # The pre-reconciliation tools/ copy used a standalone fts table whose
    # rowids do NOT correspond to chunk ids. Opening such a DB must drop
    # and rebuild it, not query it.
    path = db(tmp, "standalone")
    os.makedirs(os.path.dirname(path))
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    conn.execute(
        "INSERT INTO chunks(path, chunk_index, symbol, start_line, end_line, "
        "content, context, hash, embedding) VALUES(?,?,?,?,?,?,?,?,?)",
        ("t.py", 0, "tool_fn", 1, 5, "def tool_fn(): standalone_token", "",
         "h-t", blob("def tool_fn(): standalone_token")))
    conn.execute(
        "CREATE VIRTUAL TABLE chunks_fts USING fts5("
        "symbol, content, context, path UNINDEXED, chunk_id UNINDEXED, "
        "tokenize=\"unicode61 tokenchars '_'\")")
    conn.execute(
        "INSERT INTO chunks_fts(chunk_id, symbol, content, context, path) "
        "SELECT id, symbol, content, context, path FROM chunks")
    conn.commit()
    conn.close()

    s = Store(path)
    cols = [r[1] for r in s.conn.execute(
        "PRAGMA table_info(chunks_fts)").fetchall()]
    check("standalone table replaced (chunk_id column gone)",
          "chunk_id" not in cols, f"cols={cols}")
    check("rebuilt index still finds the chunk",
          any(h.symbol == "tool_fn"
              for h in s.lexical_search("standalone_token")))
    s.close()


def test_lexical_leg(tmp: str):
    print("== lexical_search leg ==")
    s = Store(db(tmp, "lex"))
    s.replace_file_chunks("src/auth.py", "fh", "python", [
        row("validate_token", "def validate_token(tok):\n    check(tok)",
            idx=0),
        row("helper", "def helper():\n    validate_token(None)", idx=1),
    ])
    s.replace_file_chunks("docs/notes.md", "fh", "markdown", [
        row("", "notes about validate_token usage", idx=0),
    ])
    s.commit()
    hits = s.lexical_search("validate_token")
    check("matches across files", len(hits) == 3, f"got {len(hits)}")
    check("bm25 scores negated to higher-is-better and ordered desc",
          all(hits[i].score >= hits[i + 1].score
              for i in range(len(hits) - 1)))
    pref = s.lexical_search("validate_token", path_prefix="src/")
    check("path_prefix filters", {h.path for h in pref} == {"src/auth.py"})
    check("no usable terms -> [] not error", s.lexical_search("!!") == [])
    # tokenchars '_': the identifier is ONE token, so the exact query must
    # not match a chunk that only contains the parts split apart.
    s.replace_file_chunks("split.py", "fh", "python", [
        row("other", "validate the token here", idx=0),
    ])
    s.commit()
    check("underscore identifier kept whole by tokenizer",
          all(h.path != "split.py"
              for h in s.lexical_search("validate_token")))
    s.close()


def test_symbol_boost(tmp: str):
    print("== symbol-boost rerank changes hybrid ranking ==")
    s = Store(db(tmp, "boost"))
    body_only = "def unrelated_name():\n    calls frobnicate_widget inside"
    symbol_match = "def frobnicate_widget():\n    return 1"
    s.replace_file_chunks("m.py", "fh", "python", [
        row("unrelated_name", body_only, idx=0, start=1, end=3),
        row("frobnicate_widget", symbol_match, idx=1, start=10, end=12),
    ])
    s.commit()
    # Query vector chosen to be EXACTLY the body-only chunk's embedding, so
    # the cosine leg puts it first; both chunks lexically match the term.
    # Only the symbol boost (+0.15 for a term inside the symbol name, vs
    # RRF deltas of ~1/60) can flip the order.
    qvec = np.frombuffer(blob(body_only), dtype=np.float32)
    hits = s.hybrid_search(qvec, "frobnicate_widget", top_k=2)
    check("cosine leg alone prefers body-only chunk (test premise)",
          s.search(qvec, top_k=1)[0].symbol == "unrelated_name")
    check("hybrid puts exact-symbol chunk first",
          hits and hits[0].symbol == "frobnicate_widget",
          f"got {[h.symbol for h in hits]}")
    # Sanity on the helper itself, independent of the DB.
    a = SearchHit("x.py", "no_match", 1, 2, 0.02, "frobnicate_widget", "")
    b = SearchHit("y.py", "frobnicate_widget", 1, 2, 0.01, "", "")
    check("_rerank boosts symbol match over higher raw score",
          _rerank([a, b], "frobnicate_widget")[0] is b)
    check("_rerank no-ops without terms", _rerank([a, b], "") == [a, b])
    s.close()


def test_cross_encoder_rerank_opt_in(tmp: str):
    print("== _rerank: opt-in cross-encoder path + fallback ==")
    a = SearchHit("x.py", "no_match", 1, 2, 0.02, "frobnicate_widget body", "")
    b = SearchHit("y.py", "frobnicate_widget", 1, 2, 0.01, "unrelated body", "")

    # Disabled (the default): behaves exactly like the heuristic, never
    # touches reranker.score.
    orig_enabled = reranker_mod.RERANK_ENABLED
    reranker_mod.RERANK_ENABLED = False
    try:
        check("disabled -> matches heuristic result",
              _rerank([a, b], "frobnicate_widget") ==
              _heuristic_rerank([a, b], "frobnicate_widget"))
    finally:
        reranker_mod.RERANK_ENABLED = orig_enabled

    # Enabled + dependency missing/raises: falls back to the heuristic
    # rather than failing the search request.
    reranker_mod.RERANK_ENABLED = True

    def boom(query, documents):
        raise RuntimeError("cross-encoder rerank requires: "
                            "pip install sentence-transformers")
    orig_score = reranker_mod.score
    reranker_mod.score = boom
    try:
        check("enabled but unavailable -> falls back to heuristic",
              _rerank([a, b], "frobnicate_widget") ==
              _heuristic_rerank([a, b], "frobnicate_widget"))
    finally:
        reranker_mod.score = orig_score
        reranker_mod.RERANK_ENABLED = orig_enabled

    # Enabled + a stub cross-encoder: the CE scores, not the heuristic
    # symbol boost, decide the order.
    reranker_mod.RERANK_ENABLED = True

    def stub_score(query, documents):
        # Score by document identity so we control the winner directly,
        # independent of the heuristic's symbol-name logic.
        return [1.0 if doc == "unrelated body" else 0.0 for doc in documents]
    reranker_mod.score = stub_score
    try:
        out = _rerank([a, b], "frobnicate_widget")
        check("enabled + working CE -> CE score order wins over heuristic",
              out == [b, a], f"got {[h.path for h in out]}")
    finally:
        reranker_mod.score = orig_score
        reranker_mod.RERANK_ENABLED = orig_enabled

    check("_rerank no-ops without terms", _rerank([a, b], "") == [a, b])
    check("_rerank no-ops on empty hits", _rerank([], "frobnicate_widget") == [])


def test_degradation(tmp: str):
    print("== fts unavailable -> dense-only degradation ==")
    s = Store(db(tmp, "degrade"))
    content = "def lonely(): pass"
    s.replace_file_chunks("d.py", "fh", "python", [row("lonely", content)])
    s.commit()
    # Can't build a no-FTS5 sqlite here; flip the public flag the way
    # __init__ would have on such a build. lexical_search/hybrid_search
    # consult it before touching chunks_fts.
    s.fts_available = False
    check("lexical leg returns [] when unavailable",
          s.lexical_search("lonely") == [])
    qvec = np.frombuffer(blob(content), dtype=np.float32)
    hits = s.hybrid_search(qvec, "lonely", top_k=3)
    check("hybrid still returns dense hits",
          len(hits) == 1 and hits[0].symbol == "lonely")
    check("stats reports lexical_search_enabled=False",
          s.stats()["lexical_search_enabled"] is False)
    s.close()


def test_query_term_extraction():
    print("== _fts_terms bounds/dedup ==")
    check("dedup + short-token drop",
          _fts_terms("a bb bb ccc") == ["bb", "ccc"])
    check("bounded at 12 terms",
          len(_fts_terms(" ".join(f"tok{i}" for i in range(50)))) == 12)
    check("empty/None-ish input", _fts_terms("") == [])


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="coderag-store-test-")
    try:
        test_trigger_sync(tmp)
        test_backfill_legacy(tmp)
        test_migrate_standalone_table(tmp)
        test_lexical_leg(tmp)
        test_symbol_boost(tmp)
        test_cross_encoder_rerank_opt_in(tmp)
        test_degradation(tmp)
        test_query_term_extraction()
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
