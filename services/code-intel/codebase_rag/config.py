"""Configuration: .coderag.yml at the repo root, with CLI overrides."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any

DEFAULT_IGNORES = [
    ".git", ".hg", ".svn", ".coderag", ".coderag.yml",
    "node_modules", "vendor", "dist", "build", "out", "target", "bin", "obj",
    ".venv", "venv", "__pycache__", ".mypy_cache", ".pytest_cache", ".tox",
    ".idea", ".vscode", ".gradle", ".next", ".nuxt", "coverage",
    "*.min.js", "*.min.css", "*.map", "*.lock", "package-lock.json",
    "yarn.lock", "pnpm-lock.yaml", "*.pyc", "*.class", "*.jar", "*.war",
    "*.png", "*.jpg", "*.jpeg", "*.gif", "*.ico", "*.svg", "*.pdf",
    "*.zip", "*.gz", "*.tar", "*.7z", "*.woff", "*.woff2", "*.ttf", "*.eot",
    "*.mp3", "*.mp4", "*.wav", "*.avi", "*.mov", "*.db", "*.sqlite",
    "*.so", "*.dylib", "*.dll", "*.exe", "*.bin", "*.onnx", "*.pt", "*.pkl",
]

CONFIG_FILENAME = ".coderag.yml"
STATE_DIRNAME = ".coderag"

CONFIG_TEMPLATE = """\
# codebase-rag configuration. All keys optional; defaults shown.

# Embedding provider: fastembed | sentence-transformers | openai | hash
# ("hash" is a deterministic no-ML embedder for smoke tests only)
embedder: fastembed
embed_model: BAAI/bge-small-en-v1.5
# For embedder: openai (any OpenAI-compatible endpoint, e.g. your own
# sidecar, Ollama, vLLM). API key read from OPENAI_API_KEY.
# openai_base_url: http://localhost:11434/v1

# Chunking
max_chunk_lines: 120        # split definitions bigger than this
min_chunk_lines: 4          # merge fragments smaller than this into neighbors
fallback_chunk_lines: 60    # plain-text/unknown-language chunk size
fallback_overlap_lines: 10

# Contextualization scripts: run per changed file, stdout is appended to
# every chunk's context for that file. {file} is the repo-relative path.
# The command is split shlex-style and run WITHOUT a shell ({file} is
# substituted per argv token), so pipes/redirection need an explicit
# `sh -c '... "$1"' _ {file}` wrapper.
# context_scripts:
#   - name: ctags
#     command: "ctags --output-format=json -f - {file}"
#     timeout: 15
#     max_output_chars: 1500
context_scripts: []

# Optional LLM contextualization (Anthropic-style contextual retrieval):
# a short situating paragraph per chunk, generated ONLY for new/changed
# chunks and cached by content hash. Requires ANTHROPIC_API_KEY.
llm_context: false
llm_model: claude-haiku-4-5
llm_max_file_chars: 12000

# Extra ignore globs (added to built-in defaults + .gitignore)
ignore: []

# Only index these languages (empty = all recognized languages)
languages: []
"""


@dataclass
class ContextScript:
    name: str
    command: str
    timeout: int = 15
    max_output_chars: int = 1500


@dataclass
class Config:
    root: str
    embedder: str = "fastembed"
    embed_model: str = "BAAI/bge-small-en-v1.5"
    openai_base_url: str | None = None
    max_chunk_lines: int = 120
    min_chunk_lines: int = 4
    fallback_chunk_lines: int = 60
    fallback_overlap_lines: int = 10
    context_scripts: list[ContextScript] = field(default_factory=list)
    llm_context: bool = False
    llm_model: str = "claude-haiku-4-5"
    llm_max_file_chars: int = 12000
    ignore: list[str] = field(default_factory=list)
    languages: list[str] = field(default_factory=list)

    @property
    def state_dir(self) -> str:
        return os.path.join(self.root, STATE_DIRNAME)

    @property
    def db_path(self) -> str:
        return os.path.join(self.state_dir, "index.db")

    def all_ignores(self) -> list[str]:
        return DEFAULT_IGNORES + list(self.ignore)


def load_config(root: str, overrides: dict[str, Any] | None = None) -> Config:
    root = os.path.abspath(root)
    data: dict[str, Any] = {}
    cfg_path = os.path.join(root, CONFIG_FILENAME)
    # islink: a repo's .coderag.yml is tenant content; never follow it to
    # another tenant's (or the service's) file (SEC-016).
    if os.path.isfile(cfg_path) and not os.path.islink(cfg_path):
        import yaml
        with open(cfg_path, encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
    if overrides:
        data.update({k: v for k, v in overrides.items() if v is not None})

    scripts = [
        ContextScript(
            name=s.get("name", f"script{i}"),
            command=s["command"],
            timeout=int(s.get("timeout", 15)),
            max_output_chars=int(s.get("max_output_chars", 1500)),
        )
        for i, s in enumerate(data.get("context_scripts") or [])
    ]

    known = {f.name for f in Config.__dataclass_fields__.values()}  # type: ignore[attr-defined]
    kwargs = {k: v for k, v in data.items() if k in known and k not in ("root", "context_scripts")}
    return Config(root=root, context_scripts=scripts, **kwargs)


def write_template(root: str) -> str:
    path = os.path.join(root, CONFIG_FILENAME)
    if os.path.exists(path):
        raise FileExistsError(f"{path} already exists")
    with open(path, "w", encoding="utf-8") as f:
        f.write(CONFIG_TEMPLATE)
    return path
