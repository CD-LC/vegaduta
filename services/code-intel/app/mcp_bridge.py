"""MCP stdio bridge: exposes code-intel to the platform's agents through
the EXISTING per-tenant MCP registry - zero changes to core.

The platform's DynamicMcpConnectionManager speaks MCP over stdio. This
bridge is a plain stdio MCP server (JSON-RPC 2.0, one message per line,
protocol 2024-11-05) that proxies each tool call to the code-intel HTTP
service, or - in direct mode - calls the service layer in-process.

Register per tenant like any catalog server:
  command: python3
  args:    ["-m", "app.mcp_bridge"]   (cwd: the code-intel directory)
  env:     CODE_INTEL_URL=http://code-intel:8090
           CODE_INTEL_INTERNAL_TOKEN=<shared secret>
           CODE_INTEL_TENANT=<tenant schema id>
           CODE_INTEL_REPO=<default repo_id>            (optional)
           CODE_INTEL_DIRECT=1                          (in-process, dev/tests)

Tools exposed (the agent-facing rework loop is spelled out in the
descriptions, so any competent model drives run -> fix -> rerun on its own;
the platform's Reflexion/verifier machinery reinforces it):

  search_code, code_callers, code_callees, code_impact,
  create_workspace, apply_files, run_code, workspace_diff
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

PROTOCOL_VERSION = "2024-11-05"


# ------------------------------------------------------------------ #
# backends

class HttpBackend:
    def __init__(self):
        self.base = os.environ.get("CODE_INTEL_URL",
                                   "http://localhost:8090").rstrip("/")
        # urlopen also speaks file:// (and ftp://). The base URL is operator
        # config, but refuse anything that isn't http(s) so a mis-set value
        # can never turn a tool call into a local file read (bandit B310).
        scheme = urllib.parse.urlsplit(self.base).scheme.lower()
        if scheme not in ("http", "https"):
            raise ValueError(
                f"CODE_INTEL_URL must be an http(s) URL, got scheme {scheme!r}")
        self.token = os.environ.get("CODE_INTEL_INTERNAL_TOKEN", "")
        self.tenant = os.environ.get("CODE_INTEL_TENANT", "default")

    def call(self, method: str, path: str, body: dict | None = None):
        # B310: self.base's scheme is pinned to http(s) in __init__.
        url = self.base + path
        data = None
        if body:
            if method == "GET":
                # GET params belong in the query string - FastAPI reads them
                # there and ignores request bodies on GET (the old "body on
                # GET" path silently dropped args like depth/budget).
                url += "?" + urllib.parse.urlencode(
                    {k: v for k, v in body.items() if v is not None})
            else:
                data = json.dumps(body).encode()
        req = urllib.request.Request(  # nosec B310
            url,
            data=data,
            method=method,
            headers={"Content-Type": "application/json",
                     "X-Tenant-Id": self.tenant,
                     "X-Internal-Token": self.token})
        try:
            with urllib.request.urlopen(req, timeout=1830) as resp:  # nosec B310
                return json.loads(resp.read().decode() or "null")
        except urllib.error.HTTPError as e:
            try:
                detail = json.loads(e.read().decode())
            except Exception:
                detail = {"error": str(e)}
            raise RuntimeError(detail.get("error", str(detail)))  # noqa: B904


class DirectBackend:
    """In-process service calls (CODE_INTEL_DIRECT=1) - used by tests and
    single-box dev setups where running the HTTP service is overkill."""

    def __init__(self):
        from .service import CodeIntelService
        from .settings import SETTINGS
        from .tenancy import TenantContext
        self.svc = CodeIntelService(SETTINGS)
        self.ctx = TenantContext(
            tenant_id=os.environ.get("CODE_INTEL_TENANT", "default"))

    def call(self, method: str, path: str, body: dict | None = None):
        svc, ctx, b = self.svc, self.ctx, (body or {})
        parts = [p for p in path.split("/") if p]
        # /repos/{repo}/...
        repo = parts[1] if len(parts) > 1 else ""
        tail = parts[2:] if len(parts) > 2 else []
        if tail[:1] == ["search"]:
            return svc.search(ctx, repo, b["query"],
                              top_k=b.get("top_k", 8),
                              path_prefix=b.get("path_prefix"))
        if tail[:2] == ["graph", "callers"]:
            return svc.graph_callers(ctx, repo, tail[2])
        if tail[:2] == ["graph", "callees"]:
            return svc.graph_callees(ctx, repo, tail[2])
        if tail[:2] == ["graph", "impact"]:
            return svc.graph_impact(ctx, repo, tail[2],
                                    depth=b.get("depth", 3))
        if tail[:2] == ["graph", "implementations"]:
            return svc.implementations_of(ctx, repo, tail[2],
                                          depth=b.get("depth", 4))
        if tail[:2] == ["graph", "path"]:
            return svc.call_path(ctx, repo, b["src"], b["dst"],
                                 max_depth=b.get("max_depth", 8))
        if tail == ["map"]:
            return svc.repo_map(ctx, repo, budget=b.get("budget", 16000),
                                top_n=b.get("top_n", 200))
        if tail == ["symbols"]:
            return svc.find_symbols(
                ctx, repo, query=b.get("q", ""), kind=b.get("kind") or "",
                path_prefix=b.get("path_prefix") or "",
                limit=b.get("limit", 50),
                include_tests=b.get("include_tests", True))
        if tail[:1] == ["symbols"]:
            return svc.symbol_detail(ctx, repo, tail[1],
                                     include_body=b.get("include_body", True))
        if tail == ["outline"]:
            return svc.file_outline(ctx, repo, b.get("path", ""))
        if tail[:1] == ["references"]:
            return svc.references_of(ctx, repo, tail[1])
        if tail == ["dead-code"]:
            return svc.dead_code(ctx, repo, limit=b.get("limit", 100))
        if tail[:1] == ["tests"]:
            return svc.tests_for(ctx, repo, tail[1])
        if tail == ["api-surface"]:
            return svc.api_surface(ctx, repo)
        if tail == ["hotspots"]:
            return svc.hotspots(ctx, repo, days=b.get("days", 90),
                                limit=b.get("limit", 30))
        if tail == ["import-cycles"]:
            return svc.import_cycles(ctx, repo)
        if tail == ["workspaces"]:
            return svc.workspace_create(ctx, repo)
        if len(tail) == 3 and tail[0] == "workspaces" and tail[2] == "apply":
            return svc.workspace_apply(ctx, repo, tail[1], b["files"])
        if len(tail) == 3 and tail[0] == "workspaces" and tail[2] == "run":
            return svc.workspace_run(ctx, repo, tail[1], b["command"],
                                     timeout=b.get("timeout"),
                                     attempts=b.get("attempts", 1))
        if len(tail) == 3 and tail[0] == "workspaces" and tail[2] == "diff":
            return svc.workspace_diff(ctx, repo, tail[1])
        raise RuntimeError(f"direct backend: unsupported path {path}")


def make_backend():
    if os.environ.get("CODE_INTEL_DIRECT") == "1":
        return DirectBackend()
    return HttpBackend()


# ------------------------------------------------------------------ #
# read-only surface control
#
# The write/execute tools (create_workspace, apply_files, run_code,
# workspace_diff) reach code-intel over the SAME shared X-Internal-Token that
# core uses - and code-intel now treats any internal-token caller as
# platform-pre-approved (see TenantContext.via_internal_token / the approval
# reconciliation). That trust is only safe because the ONLY internal-token
# write path is core's own approval-gated executors. So when this bridge is
# registered in the platform's MCP registry (the HTTP backend, reached by
# arbitrary agents), it must default to exposing ONLY the read-only code
# intelligence tools - otherwise an agent could drive an unapproved write
# through the bridge that code-intel would wave through as "internal".
#
# Precedence: an explicit CODE_INTEL_MCP_READONLY env always wins; otherwise
# DIRECT mode (single-box dev/tests) defaults to full tools and the HTTP/
# registry path defaults to read-only.
READ_ONLY_TOOL_NAMES = frozenset(
    {"search_code", "code_callers", "code_callees", "code_impact",
     "code_map", "find_symbols", "symbol_detail", "code_outline",
     "code_references", "code_implementations", "code_path",
     "dead_code", "code_tests", "api_surface", "code_hotspots",
     "import_cycles"})


def _read_only() -> bool:
    explicit = os.environ.get("CODE_INTEL_MCP_READONLY")
    if explicit is not None:
        return explicit.strip().lower() not in ("0", "false", "no", "off", "")
    return os.environ.get("CODE_INTEL_DIRECT") != "1"


def visible_tools() -> list[dict]:
    if _read_only():
        return [t for t in TOOLS if t["name"] in READ_ONLY_TOOL_NAMES]
    return TOOLS


# ------------------------------------------------------------------ #
# tool definitions

def _repo_prop() -> dict:
    return {"type": "string",
            "description": "Repo id registered in code-intel. Omit to use "
                           "the connection's default repo."}


TOOLS: list[dict] = [
    {"name": "search_code",
     "description": "Semantic search over an indexed codebase (code chunks "
                    "with file path, symbol and line spans). Use for "
                    "'where is X handled / implemented' questions.",
     "inputSchema": {"type": "object", "properties": {
         "query": {"type": "string"},
         "repo_id": _repo_prop(),
         "top_k": {"type": "integer", "minimum": 1, "maximum": 20},
         "path_prefix": {"type": "string",
                         "description": "Restrict to paths with this prefix"}},
         "required": ["query"]}},
    {"name": "code_callers",
     "description": "Who calls this symbol (deterministic AST-derived code "
                    "graph). Use before changing a function to see usage.",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["symbol"]}},
    {"name": "code_callees",
     "description": "What this symbol calls (code graph).",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["symbol"]}},
    {"name": "code_impact",
     "description": "Transitive blast radius of changing a symbol or file: "
                    "callers-of-callers, inheritors, importers, level by "
                    "level. Use for impact analysis before refactors.",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop(),
         "depth": {"type": "integer", "minimum": 1, "maximum": 6}},
         "required": ["symbol"]}},
    {"name": "code_map",
     "description": "Ranked skeleton of the repo: most-central symbols with "
                    "signatures (never bodies), grouped by file, under a "
                    "char budget. CALL THIS FIRST to learn a codebase's "
                    "shape before fetching anything else.",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop(),
         "budget": {"type": "integer", "minimum": 1000, "maximum": 64000},
         "top_n": {"type": "integer", "minimum": 1, "maximum": 500}}}},
    {"name": "find_symbols",
     "description": "Symbol search by name path ('Auth.login' finds "
                    "'auth.Auth.login'), with kind/path filters. Returns "
                    "signature + doc + line span, no bodies.",
     "inputSchema": {"type": "object", "properties": {
         "query": {"type": "string"}, "repo_id": _repo_prop(),
         "kind": {"type": "string",
                  "description": "function|method|class|interface|struct|enum"},
         "path_prefix": {"type": "string"},
         "limit": {"type": "integer", "minimum": 1, "maximum": 200},
         "include_tests": {"type": "boolean"}},
         "required": ["query"]}},
    {"name": "symbol_detail",
     "description": "One symbol's full record AND its exact source body - "
                    "the precise alternative to reading whole files.",
     "inputSchema": {"type": "object", "properties": {
         "qualified_name": {"type": "string"}, "repo_id": _repo_prop(),
         "include_body": {"type": "boolean"}},
         "required": ["qualified_name"]}},
    {"name": "code_outline",
     "description": "One file's symbol tree (classes, methods, signatures) "
                    "- the structural overview before reading it.",
     "inputSchema": {"type": "object", "properties": {
         "path": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["path"]}},
    {"name": "code_references",
     "description": "Every inbound mention of a symbol: calls, inherits "
                    "AND value/type references (usages that are not calls). "
                    "The 'find all references' query.",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["symbol"]}},
    {"name": "code_implementations",
     "description": "Recursive subclass/implementer tree of a class or "
                    "interface (find_implementations).",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop(),
         "depth": {"type": "integer", "minimum": 1, "maximum": 10}},
         "required": ["symbol"]}},
    {"name": "code_path",
     "description": "Shortest call chain between two symbols - 'how does X "
                    "end up calling Y'.",
     "inputSchema": {"type": "object", "properties": {
         "src": {"type": "string"}, "dst": {"type": "string"},
         "repo_id": _repo_prop(),
         "max_depth": {"type": "integer", "minimum": 1, "maximum": 16}},
         "required": ["src", "dst"]}},
    {"name": "dead_code",
     "description": "Top-level symbols with zero inbound call/inherit/"
                    "reference edges - unreachable-code review candidates "
                    "(a review list, not proof).",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop(),
         "limit": {"type": "integer", "minimum": 1, "maximum": 500}}}},
    {"name": "code_tests",
     "description": "Which test-file symbols reach this symbol - coverage "
                    "linkage between production code and its tests.",
     "inputSchema": {"type": "object", "properties": {
         "symbol": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["symbol"]}},
    {"name": "api_surface",
     "description": "The repo's HTTP routes: FastAPI/Flask/Express/Spring/"
                    "Go/Rust endpoints extracted at index time, grouped by "
                    "method. 'What does this service expose?'",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop()}}},
    {"name": "code_hotspots",
     "description": "git churn x size ranking - files changed most with the "
                    "most symbols: where bugs concentrate and review "
                    "attention belongs first.",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop(),
         "days": {"type": "integer", "minimum": 1, "maximum": 3650},
         "limit": {"type": "integer", "minimum": 1, "maximum": 200}}}},
    {"name": "import_cycles",
     "description": "File-level import-graph cycles (strongly connected "
                    "components) - architectural tangles to break.",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop()}}},
    {"name": "create_workspace",
     "description": "Create an ISOLATED scratch workspace (copy of the "
                    "repo) for code execution and fixes. Always create a "
                    "workspace before run_code/apply_files; the real "
                    "checkout is never modified.",
     "inputSchema": {"type": "object", "properties": {
         "repo_id": _repo_prop()}}},
    {"name": "apply_files",
     "description": "Write corrected FULL file contents into a workspace "
                    "(the fix step of the run->fail->fix->rerun loop). "
                    "Each entry: {path, content} or {path, delete: true}.",
     "inputSchema": {"type": "object", "properties": {
         "workspace_id": {"type": "string"}, "repo_id": _repo_prop(),
         "files": {"type": "array", "items": {"type": "object"}}},
         "required": ["workspace_id", "files"]}},
    {"name": "run_code",
     "description": "Run a shell command (build/tests/script) inside a "
                    "workspace. Returns structured success/failure with "
                    "stdout/stderr tails. IF IT FAILS: read the output, fix "
                    "the code with apply_files, and call run_code again - "
                    "repeat until it succeeds, then call workspace_diff to "
                    "produce the final patch. Set attempts>1 only to absorb "
                    "flaky tests (reruns unchanged code).",
     "inputSchema": {"type": "object", "properties": {
         "workspace_id": {"type": "string"}, "repo_id": _repo_prop(),
         "command": {"type": "string"},
         "timeout": {"type": "integer", "minimum": 1, "maximum": 1800},
         "attempts": {"type": "integer", "minimum": 1, "maximum": 5}},
         "required": ["workspace_id", "command"]}},
    {"name": "workspace_diff",
     "description": "Unified diff of every change made in a workspace - "
                    "the final patch after a successful rework loop.",
     "inputSchema": {"type": "object", "properties": {
         "workspace_id": {"type": "string"}, "repo_id": _repo_prop()},
         "required": ["workspace_id"]}},
]


def dispatch(backend, name: str, args: dict):
    repo = args.get("repo_id") or os.environ.get("CODE_INTEL_REPO", "")
    if not repo:
        raise RuntimeError("no repo_id given and CODE_INTEL_REPO not set")
    if name == "search_code":
        return backend.call("POST", f"/repos/{repo}/search", {
            "query": args["query"], "top_k": args.get("top_k", 8),
            "path_prefix": args.get("path_prefix")})
    if name == "code_callers":
        return backend.call("GET", f"/repos/{repo}/graph/callers/{args['symbol']}")
    if name == "code_callees":
        return backend.call("GET", f"/repos/{repo}/graph/callees/{args['symbol']}")
    if name == "code_impact":
        return backend.call("GET", f"/repos/{repo}/graph/impact/{args['symbol']}",
                            {"depth": args.get("depth", 3)})
    if name == "code_map":
        return backend.call("GET", f"/repos/{repo}/map",
                            {"budget": args.get("budget", 16000),
                             "top_n": args.get("top_n", 200)})
    if name == "find_symbols":
        return backend.call("GET", f"/repos/{repo}/symbols",
                            {"q": args["query"], "kind": args.get("kind"),
                             "path_prefix": args.get("path_prefix"),
                             "limit": args.get("limit", 50),
                             "include_tests": args.get("include_tests", True)})
    if name == "symbol_detail":
        return backend.call(
            "GET", f"/repos/{repo}/symbols/{args['qualified_name']}",
            {"include_body": args.get("include_body", True)})
    if name == "code_outline":
        return backend.call("GET", f"/repos/{repo}/outline",
                            {"path": args["path"]})
    if name == "code_references":
        return backend.call("GET", f"/repos/{repo}/references/{args['symbol']}")
    if name == "code_implementations":
        return backend.call(
            "GET", f"/repos/{repo}/graph/implementations/{args['symbol']}",
            {"depth": args.get("depth", 4)})
    if name == "code_path":
        return backend.call("GET", f"/repos/{repo}/graph/path",
                            {"src": args["src"], "dst": args["dst"],
                             "max_depth": args.get("max_depth", 8)})
    if name == "dead_code":
        return backend.call("GET", f"/repos/{repo}/dead-code",
                            {"limit": args.get("limit", 100)})
    if name == "code_tests":
        return backend.call("GET", f"/repos/{repo}/tests/{args['symbol']}")
    if name == "api_surface":
        return backend.call("GET", f"/repos/{repo}/api-surface")
    if name == "code_hotspots":
        return backend.call("GET", f"/repos/{repo}/hotspots",
                            {"days": args.get("days", 90),
                             "limit": args.get("limit", 30)})
    if name == "import_cycles":
        return backend.call("GET", f"/repos/{repo}/import-cycles")
    if name == "create_workspace":
        return backend.call("POST", f"/repos/{repo}/workspaces", {})
    if name == "apply_files":
        return backend.call("POST",
                            f"/repos/{repo}/workspaces/{args['workspace_id']}/apply",
                            {"files": args["files"]})
    if name == "run_code":
        return backend.call("POST",
                            f"/repos/{repo}/workspaces/{args['workspace_id']}/run",
                            {"command": args["command"],
                             "timeout": args.get("timeout"),
                             "attempts": args.get("attempts", 1)})
    if name == "workspace_diff":
        return backend.call("GET",
                            f"/repos/{repo}/workspaces/{args['workspace_id']}/diff")
    raise RuntimeError(f"unknown tool: {name}")


# ------------------------------------------------------------------ #
# MCP stdio loop (JSON-RPC 2.0, newline-delimited)

def handle(backend, msg: dict) -> dict | None:
    mid = msg.get("id")
    method = msg.get("method", "")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": msg.get("params", {}).get(
                "protocolVersion", PROTOCOL_VERSION),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "code-intel", "version": "0.1.0"}}}
    if method in ("notifications/initialized", "initialized"):
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": mid, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": mid, "result": {"tools": visible_tools()}}
    if method == "tools/call":
        params = msg.get("params", {})
        try:
            name = params.get("name", "")
            if _read_only() and name not in READ_ONLY_TOOL_NAMES:
                raise RuntimeError(
                    f"tool '{name}' is disabled: this code-intel MCP connection "
                    "is read-only (set CODE_INTEL_MCP_READONLY=false to enable "
                    "the write/execute loop). Code writes should go through the "
                    "platform's approval-gated code-workspace tools instead.")
            result = dispatch(backend, name,
                              params.get("arguments", {}) or {})
            return {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text",
                             "text": json.dumps(result, indent=2)}],
                "isError": False}}
        except Exception as e:
            return {"jsonrpc": "2.0", "id": mid, "result": {
                "content": [{"type": "text", "text": f"error: {e}"}],
                "isError": True}}
    if mid is not None:
        return {"jsonrpc": "2.0", "id": mid,
                "error": {"code": -32601, "message": f"unknown method {method}"}}
    return None


def main() -> int:
    backend = make_backend()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        reply = handle(backend, msg)
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
