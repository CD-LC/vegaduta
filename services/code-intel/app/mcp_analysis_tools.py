"""Analysis tool definitions for the MCP bridge - the deterministic
read-only analysis suite. Split out of ``app.mcp_bridge`` so both modules
stay under the file-size cap."""

from .mcp_tool_schema import _repo_prop

ANALYSIS_TOOLS: list[dict] = [
    {
        "name": "code_map",
        "description": "Ranked skeleton of the repo: most-central symbols with "
        "signatures (never bodies), grouped by file, under a "
        "char budget. CALL THIS FIRST to learn a codebase's "
        "shape before fetching anything else.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "repo_id": _repo_prop(),
                "budget": {"type": "integer", "minimum": 1000, "maximum": 64000},
                "top_n": {"type": "integer", "minimum": 1, "maximum": 500},
            },
        },
    },
    {
        "name": "find_symbols",
        "description": "Symbol search by name path ('Auth.login' finds "
        "'auth.Auth.login'), with kind/path filters. Returns "
        "signature + doc + line span, no bodies.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "repo_id": _repo_prop(),
                "kind": {
                    "type": "string",
                    "description": "function|method|class|interface|struct|enum",
                },
                "path_prefix": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200},
                "include_tests": {"type": "boolean"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "symbol_detail",
        "description": "One symbol's full record AND its exact source body - "
        "the precise alternative to reading whole files.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "qualified_name": {"type": "string"},
                "repo_id": _repo_prop(),
                "include_body": {"type": "boolean"},
            },
            "required": ["qualified_name"],
        },
    },
    {
        "name": "code_outline",
        "description": "One file's symbol tree (classes, methods, signatures) "
        "- the structural overview before reading it.",
        "inputSchema": {
            "type": "object",
            "properties": {"path": {"type": "string"}, "repo_id": _repo_prop()},
            "required": ["path"],
        },
    },
    {
        "name": "code_references",
        "description": "Every inbound mention of a symbol: calls, inherits "
        "AND value/type references (usages that are not calls). "
        "The 'find all references' query.",
        "inputSchema": {
            "type": "object",
            "properties": {"symbol": {"type": "string"}, "repo_id": _repo_prop()},
            "required": ["symbol"],
        },
    },
    {
        "name": "code_implementations",
        "description": "Recursive subclass/implementer tree of a class or "
        "interface (find_implementations).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "symbol": {"type": "string"},
                "repo_id": _repo_prop(),
                "depth": {"type": "integer", "minimum": 1, "maximum": 10},
            },
            "required": ["symbol"],
        },
    },
    {
        "name": "code_path",
        "description": "Shortest call chain between two symbols - 'how does X end up calling Y'.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "src": {"type": "string"},
                "dst": {"type": "string"},
                "repo_id": _repo_prop(),
                "max_depth": {"type": "integer", "minimum": 1, "maximum": 16},
            },
            "required": ["src", "dst"],
        },
    },
    {
        "name": "dead_code",
        "description": "Top-level symbols with zero inbound call/inherit/"
        "reference edges - unreachable-code review candidates "
        "(a review list, not proof).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "repo_id": _repo_prop(),
                "limit": {"type": "integer", "minimum": 1, "maximum": 500},
            },
        },
    },
    {
        "name": "code_tests",
        "description": "Which test-file symbols reach this symbol - coverage "
        "linkage between production code and its tests.",
        "inputSchema": {
            "type": "object",
            "properties": {"symbol": {"type": "string"}, "repo_id": _repo_prop()},
            "required": ["symbol"],
        },
    },
    {
        "name": "api_surface",
        "description": "The repo's HTTP routes: FastAPI/Flask/Express/Spring/"
        "Go/Rust endpoints extracted at index time, grouped by "
        "method. 'What does this service expose?'",
        "inputSchema": {"type": "object", "properties": {"repo_id": _repo_prop()}},
    },
    {
        "name": "code_hotspots",
        "description": "git churn x size ranking - files changed most with the "
        "most symbols: where bugs concentrate and review "
        "attention belongs first.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "repo_id": _repo_prop(),
                "days": {"type": "integer", "minimum": 1, "maximum": 3650},
                "limit": {"type": "integer", "minimum": 1, "maximum": 200},
            },
        },
    },
    {
        "name": "import_cycles",
        "description": "File-level import-graph cycles (strongly connected "
        "components) - architectural tangles to break.",
        "inputSchema": {"type": "object", "properties": {"repo_id": _repo_prop()}},
    },
]
