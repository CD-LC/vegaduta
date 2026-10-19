"""codebase-rag: incremental, contextualized RAG ingestion for codebases.

Point it at any git repository (or plain folder). It chunks source files
along AST boundaries (tree-sitter, all major languages), prepends structural
context to every chunk (file path, symbol chain, imports), optionally runs
your own contextualization scripts and/or an LLM summarizer, embeds, and
stores everything in a local SQLite index. Re-runs are incremental: git
diff picks the changed files, per-chunk content hashes ensure only chunks
whose text or context actually changed are re-embedded.
"""

__version__ = "0.1.0"
