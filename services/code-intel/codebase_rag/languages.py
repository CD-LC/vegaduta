"""Language registry: extension -> language, tree-sitter grammar names, and
regex fallbacks used when tree-sitter is unavailable for a language."""

from __future__ import annotations

# extension (lowercase, with dot) -> canonical language name
EXTENSION_MAP: dict[str, str] = {
    ".py": "python", ".pyi": "python",
    ".js": "javascript", ".mjs": "javascript", ".cjs": "javascript", ".jsx": "javascript",
    ".ts": "typescript", ".mts": "typescript", ".cts": "typescript",
    ".tsx": "tsx",
    ".java": "java",
    ".go": "go",
    ".rs": "rust",
    ".c": "c", ".h": "c",
    ".cpp": "cpp", ".cc": "cpp", ".cxx": "cpp", ".hpp": "cpp", ".hh": "cpp", ".hxx": "cpp",
    ".cs": "csharp",
    ".rb": "ruby",
    ".php": "php",
    ".kt": "kotlin", ".kts": "kotlin",
    ".swift": "swift",
    ".scala": "scala", ".sc": "scala",
    ".dart": "dart",
    ".lua": "lua",
    ".r": "r",
    ".jl": "julia",
    ".ex": "elixir", ".exs": "elixir",
    ".erl": "erlang", ".hrl": "erlang",
    ".hs": "haskell",
    ".ml": "ocaml", ".mli": "ocaml",
    ".sh": "bash", ".bash": "bash", ".zsh": "bash",
    ".ps1": "powershell", ".psm1": "powershell",
    ".sql": "sql",
    ".html": "html", ".htm": "html",
    ".css": "css", ".scss": "scss",
    ".vue": "vue",
    ".svelte": "svelte",
    ".tf": "hcl", ".hcl": "hcl",
    ".proto": "proto",
    ".graphql": "graphql", ".gql": "graphql",
    ".yaml": "yaml", ".yml": "yaml",
    ".json": "json",
    ".toml": "toml",
    ".xml": "xml",
    ".md": "markdown", ".rst": "markdown", ".txt": "text",
    ".gradle": "groovy", ".groovy": "groovy",
    ".m": "objc", ".mm": "objc",
    ".zig": "zig",
    ".nim": "nim",
    ".sol": "solidity",
}

SPECIAL_FILENAMES: dict[str, str] = {
    "dockerfile": "dockerfile",
    "makefile": "make",
    "cmakelists.txt": "cmake",
    "jenkinsfile": "groovy",
    "build.gradle": "groovy",
    "pom.xml": "xml",
}

# canonical language name -> tree-sitter-language-pack grammar name
# (identity unless it differs)
TS_GRAMMAR_MAP: dict[str, str] = {
    "csharp": "csharp",
    "objc": "objc",
    "powershell": "powershell",
    "proto": "proto",
    "vue": "vue",
    "svelte": "svelte",
    "text": "",       # never parsed
    "markdown": "markdown",
}

# Node-type name fragments that mark a "definition" boundary across grammars.
# Generic on purpose: tree-sitter grammars are wildly inconsistent, but their
# definition node types almost always contain one of these substrings.
DEFINITION_TYPE_FRAGMENTS = (
    "function_definition", "function_declaration", "function_item",
    "method_definition", "method_declaration",
    "class_definition", "class_declaration", "class_specifier",
    "interface_declaration", "trait_item", "trait_declaration",
    "struct_item", "struct_specifier", "struct_declaration",
    "enum_item", "enum_declaration", "enum_specifier",
    "impl_item", "implementation",
    "module", "mod_item", "namespace_definition", "namespace_declaration",
    "type_declaration", "type_item", "type_alias",
    "constructor_declaration", "destructor",
    "arrow_function",  # only kept when bound at top level
    "lexical_declaration",  # js/ts const foo = () => {} at top level
    "func_literal",
    "protocol_declaration", "extension_declaration",  # swift
    "object_declaration", "companion_object",         # kotlin
    "annotation_type_declaration", "record_declaration",  # java
    "singleton_method", "method",                     # ruby
    "subroutine", "task_definition",
)

# Regex fallback per language family: patterns that open a new definition.
# Used only when tree-sitter is not installed or has no grammar for the file.
FALLBACK_DEF_PATTERNS: dict[str, str] = {
    "python": r"^(async\s+def|def|class)\s+\w+",
    "javascript": r"^(export\s+)?(default\s+)?(async\s+)?(function\s*\*?\s*\w+|class\s+\w+|const\s+\w+\s*=\s*(async\s*)?(\(|function))",
    "typescript": r"^(export\s+)?(default\s+)?(abstract\s+)?(async\s+)?(function\s*\*?\s*\w+|class\s+\w+|interface\s+\w+|enum\s+\w+|type\s+\w+\s*=|const\s+\w+\s*=\s*(async\s*)?(\(|function))",
    "tsx": r"^(export\s+)?(default\s+)?(async\s+)?(function\s*\*?\s*\w+|class\s+\w+|interface\s+\w+|const\s+\w+)",
    "java": r"^\s*(public|private|protected|static|final|abstract|synchronized|@\w+|\s)*\s*(class|interface|enum|record)\s+\w+|^\s{0,4}(public|private|protected)\s+[\w<>\[\],\s]+\s+\w+\s*\(",
    "go": r"^(func|type)\s+",
    "rust": r"^\s*(pub\s+)?(async\s+)?(fn|struct|enum|trait|impl|mod)\s+",
    "c": r"^\w[\w\s\*]*\s+\**\w+\s*\([^;]*$|^(struct|enum|union|typedef)\s+",
    "cpp": r"^[\w:<>~]+[\w\s\*&:<>,~]*\s+[\w:~]+\s*\([^;]*$|^(class|struct|enum|namespace|template)\b",
    "csharp": r"^\s*(public|private|protected|internal|static|sealed|abstract|partial|\s)*\s*(class|interface|struct|enum|record)\s+\w+|^\s*(public|private|protected|internal)\s+[\w<>\[\],\s]+\s+\w+\s*\(",
    "ruby": r"^\s*(def|class|module)\s+",
    "php": r"^\s*(abstract\s+|final\s+)?(class|interface|trait|function)\s+\w+|^\s*(public|private|protected|static|\s)+function\s+\w+",
    "kotlin": r"^\s*(open\s+|data\s+|sealed\s+|abstract\s+|private\s+|internal\s+|suspend\s+)*\s*(class|interface|object|fun)\s+",
    "swift": r"^\s*(public\s+|private\s+|internal\s+|open\s+|final\s+|static\s+)*\s*(class|struct|enum|protocol|extension|func)\s+",
    "scala": r"^\s*(case\s+)?(class|object|trait|def)\s+",
    "dart": r"^\s*(abstract\s+)?(class|enum|mixin)\s+\w+|^\s*[\w<>]+\s+\w+\s*\(",
    "lua": r"^\s*(local\s+)?function\s+",
    "elixir": r"^\s*(def|defp|defmodule|defmacro)\s+",
    "bash": r"^\s*(function\s+\w+|\w+\s*\(\)\s*\{)",
    "powershell": r"^\s*function\s+[\w-]+",
    "sql": r"^\s*(CREATE|ALTER)\s+(OR\s+REPLACE\s+)?(TABLE|VIEW|FUNCTION|PROCEDURE|TRIGGER|INDEX)\b",
    "hcl": r'^\s*(resource|module|variable|output|provider|data|locals)\s',
}

# Import/include extraction per language family (first N matches are kept).
IMPORT_PATTERNS: dict[str, str] = {
    "python": r"^\s*(?:from\s+[\w\.]+\s+import\s+.+|import\s+[\w\., ]+)",
    "javascript": r"^\s*(?:import\s.+?from\s+['\"].+?['\"]|const\s+.+?=\s*require\(['\"].+?['\"]\))",
    "typescript": r"^\s*import\s.+?from\s+['\"].+?['\"]",
    "tsx": r"^\s*import\s.+?from\s+['\"].+?['\"]",
    "java": r"^\s*import\s+[\w\.\*]+;",
    "go": r"^\s*(?:import\s+\(|\t?\"[\w\./\-]+\"|import\s+\"[\w\./\-]+\")",
    "rust": r"^\s*use\s+[\w:{}\*, ]+;",
    "c": r"^\s*#include\s+[<\"].+?[>\"]",
    "cpp": r"^\s*#include\s+[<\"].+?[>\"]",
    "csharp": r"^\s*using\s+[\w\.]+;",
    "ruby": r"^\s*require(_relative)?\s+['\"].+?['\"]",
    "php": r"^\s*(use\s+[\w\\\\]+;|require(_once)?\s|include(_once)?\s)",
    "kotlin": r"^\s*import\s+[\w\.\*]+",
    "swift": r"^\s*import\s+\w+",
    "scala": r"^\s*import\s+[\w\.\{\}, ]+",
    "elixir": r"^\s*(alias|import|use|require)\s+[\w\.]+",
}


def detect_language(path: str) -> str | None:
    """Return canonical language for a path, or None if not a code/text file
    we know how to handle."""
    import os
    name = os.path.basename(path).lower()
    if name in SPECIAL_FILENAMES:
        return SPECIAL_FILENAMES[name]
    _, ext = os.path.splitext(name)
    return EXTENSION_MAP.get(ext)


def ts_grammar_for(language: str) -> str | None:
    """tree-sitter-language-pack grammar name for a language, or None."""
    g = TS_GRAMMAR_MAP.get(language, language)
    return g or None
