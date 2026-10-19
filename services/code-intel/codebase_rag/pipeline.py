"""Incremental sync: git diff picks changed files, per-chunk hashes pick
changed chunks, and only those get (re)contextualized and (re)embedded."""

from __future__ import annotations

import hashlib
import sys
from dataclasses import dataclass, field

from . import discovery, gitstate
from .chunker import chunk_file
from .config import Config
from .contextualizer import (
    LlmContextualizer,
    chunk_content_key,
    extract_imports,
    run_context_scripts,
    structural_context,
)
from .embedders import Embedder
from .store import Store


@dataclass
class SyncStats:
    files_scanned: int = 0
    files_changed: int = 0
    files_deleted: int = 0
    chunks_total: int = 0
    chunks_embedded: int = 0
    chunks_reused: int = 0
    llm_summaries_generated: int = 0
    llm_summaries_cached: int = 0
    engines: dict = field(default_factory=dict)


def _file_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _config_fingerprint(cfg: Config) -> str:
    """Hash of every setting that changes chunk content or context. When it
    changes, previously indexed chunks are stale even though no source file
    moved - force a full rescan."""
    h = hashlib.sha256()
    parts = (
        cfg.max_chunk_lines, cfg.min_chunk_lines,
        cfg.fallback_chunk_lines, cfg.fallback_overlap_lines,
        cfg.llm_context, cfg.llm_model, cfg.llm_max_file_chars,
        [(s.name, s.command, s.max_output_chars) for s in cfg.context_scripts],
    )
    h.update(repr(parts).encode("utf-8"))
    return h.hexdigest()


def _chunk_hash(embed_input: str, embedder_identity: str) -> str:
    h = hashlib.sha256()
    h.update(embedder_identity.encode("utf-8"))
    h.update(b"\0")
    h.update(embed_input.encode("utf-8"))
    return h.hexdigest()


def _log(msg: str, quiet: bool) -> None:
    if not quiet:
        print(msg, file=sys.stderr)


def _select_changed(cfg: Config, store: Store, full: bool,
                    quiet: bool) -> tuple[set[str], set[str]]:
    """(files to (re)index, files to delete) - both repo-relative."""
    known = store.known_files()
    use_git = gitstate.is_git_repo(cfg.root)
    last_commit = store.get_meta("last_commit")

    if (not full and use_git and last_commit
            and gitstate.commit_exists(cfg.root, last_commit)):
        diff_changed, diff_deleted = gitstate.diff_since(cfg.root, last_commit)
        dirty_changed, dirty_deleted = gitstate.dirty_files(cfg.root)
        candidates = discovery.filter_eligible(
            cfg, diff_changed | dirty_changed)
        # Anything indexed before but untouched by git stays as-is.
        deleted = {p for p in (diff_deleted | dirty_deleted) if p in known}
        # Also catch files deleted while git state was clean (edge cases)
        deleted |= {p for p in known
                    if not gitstate.file_exists(cfg.root, p)}
        _log(f"git: {len(candidates)} candidate file(s) since "
             f"{last_commit[:10]}, {len(deleted)} deleted", quiet)
        return candidates, deleted

    # Full walk (first run, --full, non-git folder, or unreachable commit):
    # still incremental at file level via stored file hashes.
    all_files = discovery.discover_all(cfg)
    deleted = {p for p in known if p not in set(all_files)}
    _log(f"walk: {len(all_files)} indexable file(s), "
         f"{len(deleted)} previously-indexed file(s) gone", quiet)
    return set(all_files), deleted


def sync(cfg: Config, store: Store, embedder: Embedder,
         full: bool = False, quiet: bool = False) -> SyncStats:
    stats = SyncStats()

    # A different embedder/model invalidates every stored hash; force full.
    prev_identity = store.get_meta("embedder_identity")
    if prev_identity and prev_identity != embedder.identity:
        _log(f"embedder changed ({prev_identity} -> {embedder.identity}); "
             "re-embedding everything", quiet)
        full = True

    # Changed chunking/contextualization settings also stale every chunk.
    fingerprint = _config_fingerprint(cfg)
    prev_fingerprint = store.get_meta("config_fingerprint")
    if prev_fingerprint and prev_fingerprint != fingerprint:
        _log("chunking/context configuration changed; rescanning all files",
             quiet)
        full = True

    candidates, deleted = _select_changed(cfg, store, full, quiet)
    known = store.known_files()

    for path in sorted(deleted):
        store.delete_file(path)
        stats.files_deleted += 1

    llm = LlmContextualizer(cfg) if cfg.llm_context else None

    for path in sorted(candidates):
        stats.files_scanned += 1
        text = discovery.read_text(cfg, path)
        if text is None:
            continue
        fhash = _file_hash(text)
        if known.get(path) == fhash and not full:
            continue  # git listed it, content is identical (e.g. revert)

        from .languages import detect_language
        language = detect_language(path) or "text"
        chunks = chunk_file(cfg, path, text, language)
        engine = getattr(chunk_file, "last_engine", "?")
        stats.engines[engine] = stats.engines.get(engine, 0) + 1
        if not chunks:
            store.delete_file(path)
            continue

        imports = extract_imports(text, language)
        script_out = run_context_scripts(cfg, path)

        # ---- optional LLM summaries, cached by content key ----
        summaries: dict[int, str] = {}
        if llm is not None:
            keys = [chunk_content_key(ch, fhash) for ch in chunks]
            cached = store.get_llm_summaries(keys)
            for i, (ch, key) in enumerate(zip(chunks, keys, strict=False)):
                if key in cached:
                    summaries[i] = cached[key]
                    stats.llm_summaries_cached += 1
                else:
                    try:
                        summary = llm.summarize(ch, text)
                    except Exception as e:
                        _log(f"  llm context failed for {path}#{i}: {e}", quiet)
                        summary = ""
                    if summary:
                        store.put_llm_summary(key, summary)
                        summaries[i] = summary
                        stats.llm_summaries_generated += 1

        # ---- build embed inputs + hashes ----
        rows_meta = []  # (chunk, context, embed_input, chunk_hash)
        for i, ch in enumerate(chunks):
            context = structural_context(ch, imports, script_out)
            if i in summaries:
                context += "\nSummary: " + summaries[i]
            embed_input = context + "\n\n" + ch.content
            rows_meta.append(
                (ch, context, embed_input,
                 _chunk_hash(embed_input, embedder.identity)))

        # ---- reuse embeddings whose hash is unchanged ----
        existing = store.chunk_embeddings_for_file(path)
        missing = [m for m in rows_meta if m[3] not in existing]
        # global reuse (moved code between files)
        global_hits: dict[str, bytes] = {}
        if missing:
            global_hits = store.lookup_embeddings_by_hash(
                [m[3] for m in missing])
            missing = [m for m in missing if m[3] not in global_hits]

        new_vecs: dict[str, bytes] = {}
        if missing:
            vecs = embedder.embed([m[2] for m in missing])
            for m, vec in zip(missing, vecs, strict=False):
                new_vecs[m[3]] = vec.astype("float32").tobytes()

        rows = []
        for ch, context, embed_input, chash in rows_meta:
            blob = (existing.get(chash) or global_hits.get(chash)
                    or new_vecs.get(chash))
            if blob is None:  # defensive: should be unreachable
                blob = embedder.embed([embed_input])[0].astype("float32").tobytes()
                new_vecs[chash] = blob
            reused = chash in existing or chash in global_hits
            stats.chunks_reused += int(reused)
            stats.chunks_embedded += int(not reused)
            stats.chunks_total += 1
            rows.append((ch.index, ch.symbol, ch.start_line, ch.end_line,
                         ch.content, context, chash, blob))

        store.replace_file_chunks(path, fhash, language, rows)
        stats.files_changed += 1
        _log(f"  {path}: {len(rows)} chunk(s), "
             f"{sum(1 for m in rows_meta if m[3] in new_vecs)} embedded "
             f"[{engine}]", quiet)

    # ---- record position ----
    head = gitstate.head_commit(cfg.root)
    if head:
        store.set_meta("last_commit", head)
    store.set_meta("embedder_identity", embedder.identity)
    store.set_meta("config_fingerprint", fingerprint)
    import time
    store.set_meta("last_indexed_at", str(time.time()))
    store.commit()
    return stats
