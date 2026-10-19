# codebase_rag exists twice in this repo — this copy is CANONICAL

`code-intel/codebase_rag/` and `tools/codebase-rag/codebase_rag/` are two
physical copies of the same Python package. **This one (`code-intel/`) is
canonical**: `code-intel/app/service.py` imports it directly and
`code-intel/Dockerfile`'s build context bakes it into the deployed image.
`tools/codebase-rag/` is the standalone CLI/library packaging (mirror).

Rules:

- **Edit here, never only in the mirror.** After editing, run
  `bash scripts/dev/sync-ragtree.sh` to propagate to
  `tools/codebase-rag/codebase_rag/`.
- CI enforces this: `scripts/ci/ragtree-sync-check.sh` (wired into
  `.github/workflows/static-gates.yml`) fails on any new divergence
  between the two copies.
- Two files (`cli.py`, `store.py`) had **already diverged** with two
  independent hybrid-FTS5 implementations before this guard existed; they
  are frozen in `scripts/ci/ragtree-sync.baseline` pending human
  reconciliation. The sync script skips them unless run with `--all`.
- Physical dedup (one package, symlink/build-time copy) is deliberately
  NOT done here — the Docker build context depends on this vendored
  layout, and restructuring that is a separate decision.
