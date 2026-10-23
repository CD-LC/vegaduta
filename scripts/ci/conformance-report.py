#!/usr/bin/env python3
"""Generate docs/api/conformance.md - the standards-conformance table.

ROADMAP.md section 4 asks for "a conformance table for MCP, A2A, AG-UI and
OpenTelemetry, generated from tests rather than written by hand". This is the
static-gates version of that: no JUnit, no Maven, no toolchain - one Python
3.12 stdlib script that grep-checks the source tree for evidence of each
capability and renders a deterministic Markdown table, the same model as
core/tools/module-graph/gen_module_graph.py (whose header comment this doc's
header mirrors).

Each row in CAPABILITIES carries:

  standard    MCP / A2A / AG-UI / OpenTelemetry
  capability  one-line description (flag-gating noted inline)
  claimed     what README.md / ROADMAP.md assert for it today
              ("implemented", "planned", or "not claimed") - used ONLY for
              the drift section, never to set the status
  checks      [(repo-relative file, [regexes]); ...] - a check passes when the
              file exists AND every regex matches somewhere in it; an empty
              regex list is an existence check
  see         extra file paths to list under Evidence even when every check
              fails (e.g. the file that documents a deliberate gap)

Status is COMPUTED, never asserted:

  implemented  every check passed
  partial      some checks passed, some failed
  planned      no check passed AND the roadmap claims it as planned
  missing      no check passed and nothing claims it exists

Honesty rule (mirrors AGENTS.md rule 11): a row must not say "implemented"
without file evidence in the tree, and rows for capabilities the protocols
define but this codebase deliberately does not offer (A2A push notifications
and tasks/resubscribe, AG-UI's binary transport and optional event types)
stay in the table marked "missing" rather than being silently omitted.

Usage (from anywhere in the repo):
    python3 scripts/ci/conformance-report.py            # (re)write the doc
    python3 scripts/ci/conformance-report.py --check    # exit 1 if stale (CI gate)
    python3 scripts/ci/conformance-report.py --stdout   # print, don't write
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = REPO / "docs" / "api" / "conformance.md"

AGENTS_TOOLS = "core/agents-tools/src/main/java/com/agenticai/core/agents/tools"
# A2A and AG-UI moved to core/agents-interop (ADR-0023); the card signing key stays in agents.
A2A = "core/agents-interop/src/main/java/com/agenticai/core/agents/a2a"
A2A_KERNEL = "core/agents/src/main/java/com/agenticai/core/agents/a2a"
AGUI = "core/agents-interop/src/main/java/com/agenticai/core/agents/agui"
CHAT = "core/chat/src/main/java/com/agenticai/core/chat"
MCPSERVER = "core/mcpserver/src/main/java/com/agenticai/core/mcpserver"
MCP = "core/mcp/src/main/java/com/agenticai/core/mcp"
APPCONFIG = "core/app-config/src/main/resources"



def served(op: str) -> str:
    """Regex: the dispatcher has a `case ... "<op>" ... ->` arm whose body is not a `throw`.

    A bare grep for the operation name also matches the arm that refuses it
    (`case "ListTasks", "SubscribeToTask" -> throw A2aError.unsupportedOperation(...)`), which
    used to count an operation the server answers with UnsupportedOperationError as implemented.
    """
    return rf'case\s+[^>]*"{op}"[^>]*->\s*(?!\s|throw\b)'


def not_refused(op: str) -> str:
    """Regex that matches only a file that never refuses `<op>` via A2aError.unsupportedOperation."""
    return rf'\A(?![\s\S]*unsupportedOperation\("{op}"\))'


# fmt: off
CAPABILITIES = [
    # ------------------------------------------------------------- MCP ----
    {
        "standard": "MCP",
        "capability": "Server: Streamable HTTP endpoint `POST /mcp` (`mcpserver` profile, Spring AI MCP server)",
        "claimed": "implemented",
        "checks": [
            (f"{APPCONFIG}/application-mcpserver.yml",
             [r"protocol: STREAMABLE", r"mcp-endpoint: /mcp"]),
            (f"{MCPSERVER}/McpServerToolConfig.java", [r"FilterRegistrationBean", r'"/mcp/\*"']),
            ("core/security/src/main/java/com/agenticai/core/security/SecurityConfig.java",
             [r'"/mcp/\*\*"']),
            ("core/mcpserver/pom.xml", [r"spring-ai-starter-mcp-server-webmvc"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Server: curated tools `list_my_agents`, `call_agent`, `list_my_workflows`, `call_workflow`, `search_knowledge`",
        "claimed": "implemented",
        "checks": [
            (f"{MCPSERVER}/tools/McpListAgentsToolCallback.java", [r'"list_my_agents"']),
            (f"{MCPSERVER}/tools/McpCallAgentToolCallback.java", [r'"call_agent"']),
            (f"{MCPSERVER}/tools/McpListMyWorkflowsToolCallback.java", [r'"list_my_workflows"']),
            (f"{MCPSERVER}/tools/McpCallWorkflowToolCallback.java", [r'"call_workflow"']),
            (f"{MCPSERVER}/tools/McpSearchKnowledgeToolCallback.java", [r'"search_knowledge"']),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Server: async task tools `start_agent_task`/`get_task`/`cancel_task` (off by default: `app.mcpserver.async-tasks.enabled`)",
        "claimed": "not claimed",
        "checks": [
            (f"{MCPSERVER}/tools/McpAgentTaskToolCallbacks.java",
             [r"start_agent_task", r"get_task", r"cancel_task"]),
            (f"{APPCONFIG}/application-mcpserver.yml", [r"async-tasks:"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Server: per-grant dynamic tools `agent_*`/`workflow_*`/`get_run` via `tools/list`+`tools/call` hook (off by default: `app.mcp-server.expose-agents.enabled`)",
        "claimed": "not claimed",
        "checks": [
            (f"{MCPSERVER}/expose/McpExposureRpcHandler.java", [r"tools/list", r"tools/call"]),
            (f"{MCPSERVER}/expose/McpExposedToolCatalog.java", [r'GET_RUN_TOOL = "get_run"']),
            (f"{APPCONFIG}/application-mcpserver.yml", [r"expose-agents:"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Server: API-key auth + per-key rate limiting (`vmcp_` bearer keys, per-tenant grants)",
        "claimed": "implemented",
        "checks": [
            (f"{MCPSERVER}/McpApiKeyAuthFilter.java", [r"vmcp_", r"TenantContext"]),
            (f"{MCPSERVER}/McpServerRateLimiter.java", []),
            (f"{MCPSERVER}/McpServerAccessGrantService.java", []),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Client: stdio transport (incl. env-isolated spawn and `mcp-stdio-runner` sidecar routing)",
        "claimed": "implemented",
        "checks": [
            (f"{AGENTS_TOOLS}/McpTransportFactory.java", [r"StdioClientTransport"]),
            (f"{MCP}/McpServerType.java", [r"STDIO"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Client: SSE transport",
        "claimed": "implemented",
        "checks": [
            (f"{AGENTS_TOOLS}/McpTransportFactory.java", [r"HttpClientSseClientTransport"]),
            (f"{MCP}/McpServerType.java", [r"SSE"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Client: Streamable HTTP transport",
        "claimed": "implemented",
        "checks": [
            (f"{AGENTS_TOOLS}/McpTransportFactory.java", [r"HttpClientStreamableHttpTransport"]),
            (f"{MCP}/McpServerType.java", [r"STREAMABLE_HTTP"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Client: OAuth 2.0 for remote servers (RFC 9728/8414 discovery, PKCE, dynamic registration, refresh; off by default: `app.mcp.oauth.enabled`)",
        "claimed": "implemented",
        "checks": [
            (f"{MCP}/oauth/McpOAuthService.java", [r"class McpOAuthService"]),
            (f"{MCP}/oauth/McpOAuthPkce.java", [r"challengeFor"]),
            (f"{MCP}/oauth/McpOAuthDiscovery.java", [r"class McpOAuthDiscovery"]),
            (f"{MCP}/oauth/McpOAuthController.java", []),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Client: `resources/read` for MCP Apps `ui://` resources (off by default: `app.mcp.apps.enabled`)",
        "claimed": "not claimed",
        "checks": [
            (f"{AGENTS_TOOLS}/mcpapps/McpAppsService.java", [r"readResource", r"ui://"]),
            (f"{AGENTS_TOOLS}/DynamicMcpConnectionManager.java", [r"resources/read"]),
        ],
        "see": [],
    },
    {
        "standard": "MCP",
        "capability": "Registry: per-tenant server registration + official MCP registry catalog search",
        "claimed": "implemented",
        "checks": [
            (f"{MCP}/McpRegistryController.java", [r'"/api/mcp/registry"']),
            (f"{MCP}/registry/OfficialMcpRegistryClient.java", [r"registry\.modelcontextprotocol\.io"]),
        ],
        "see": [],
    },
    # ------------------------------------------------------------- A2A ----
    {
        "standard": "A2A",
        "capability": "Platform Agent Card at `/.well-known/agent-card.json` (+ legacy `/.well-known/agent.json`)",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aAgentCardController.java",
             [r"/\.well-known/agent-card\.json", r"/\.well-known/agent\.json"]),
            (f"{A2A}/A2aAgentCardService.java", [r"platformCard"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Per-agent card `GET /api/agents/{agentId}/a2a/agent-card.json` (RUNNER-gated)",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aAgentCardController.java", [r"/api/agents/\{agentId\}/a2a/agent-card\.json"]),
            (f"{A2A}/A2aAgentCardService.java", [r"agentCard"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Signed Agent Cards (detached JWS / Ed25519 `signatures`, public key at `/.well-known/jwks.json`)",
        "claimed": "implemented",
        "checks": [
            (f"{A2A_KERNEL}/A2aCardSigningService.java", [r"Ed25519", r"signatures"]),
            (f"{A2A}/A2aAgentCardService.java", [r"signatures"]),
            (f"{A2A}/A2aAgentCardController.java", [r"/\.well-known/jwks\.json"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "JSON-RPC `message/send` (0.3) / `SendMessage` (1.0) at `POST /api/a2a` and `POST /api/agents/{agentId}/a2a`",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java",
             [r'"message/send"', r'"SendMessage"', r'"/api/a2a"', r"/api/agents/\{agentId\}/a2a"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "JSON-RPC `message/stream` / `SendStreamingMessage` (SSE task + status/artifact frames)",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java",
             [r'"message/stream"', r'"SendStreamingMessage"', r"TEXT_EVENT_STREAM_VALUE"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "JSON-RPC `tasks/get` / `GetTask`",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java", [r'"tasks/get"', r'"GetTask"']),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "JSON-RPC `tasks/cancel` / `CancelTask`",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java", [r'"tasks/cancel"', r'"CancelTask"']),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Dual wire dialects 0.3 and 1.0 selected by the `A2A-Version` header",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aVersion.java", [r"A2A-Version", r'"0\.3"', r'"1\.0"']),
            (f"{A2A}/A2aWire.java", []),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "HTTP+JSON/REST binding (spec section 11): `POST /message:send`, `POST /message:stream` (SSE), `GET /tasks/{id}`, `POST /tasks/{id}:cancel` under `/api/a2a/rest` and `/api/agents/{agentId}/a2a/rest`",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aRestController.java",
             [r'/message:send', r'/message:stream', r'/tasks/\{id\}"', r'/tasks/\{id\}:cancel',
              r'application/a2a\+json', r'AGENT_BASE = "/api/agents/\{agentId\}/a2a/rest"']),
            (f"{A2A}/A2aAgentCardService.java", [r'"HTTP\+JSON"']),
            ("core/agents-interop/src/test/java/com/agenticai/core/agents/a2a/A2aRestControllerTest.java", [r"springRoutesTheColonVerbs"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "REST errors as `google.rpc.Status` with an `ErrorInfo` detail (domain `a2a-protocol.org`) and the section 5.4 HTTP statuses",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aRestController.java",
             [r"google\.rpc\.ErrorInfo", r'ERROR_DOMAIN = "a2a-protocol\.org"', r"TASK_NOT_CANCELABLE", r"VERSION_NOT_SUPPORTED"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Agent Card declares both bindings in `supportedInterfaces` (`JSONRPC` first, then `HTTP+JSON`), each with `protocolVersion`",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aAgentCardService.java",
             [r'"protocolBinding",\s*"JSONRPC"', r'"protocolBinding",\s*"HTTP\+JSON"', r'"protocolVersion"']),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "`ListTasks` (JSON-RPC and REST `GET /tasks`) returns tasks instead of an unsupported error",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java", [served("ListTasks"), not_refused("ListTasks")]),
            (f"{A2A}/A2aRestController.java", [not_refused("ListTasks")]),
            (f"{A2A}/A2aTaskStore.java", [r"listFor|list\("]),
        ],
        "see": [f"{A2A}/A2aRestController.java"],
    },
    {
        "standard": "A2A",
        "capability": "`SubscribeToTask` (re-attach to a running task's stream)",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java", [served("SubscribeToTask"), not_refused("SubscribeToTask")]),
            (f"{A2A}/A2aRestController.java", [not_refused("SubscribeToTask")]),
        ],
        "see": [f"{A2A}/A2aRestController.java"],
    },
    {
        "standard": "A2A",
        "capability": "gRPC binding (spec section 10)",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aGrpcService.java", []),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Agent Card caching headers (`Cache-Control`; `ETag` is a spec SHOULD)",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aAgentCardController.java", [r"CacheControl"]),
            (f"{A2A}/A2aAgentCardController.java", [r"\.eTag\("]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Outbound client: `a2a_call_agent` tool with signed-card verification and an egress allow-list (off by default: `app.a2a.client.enabled`)",
        "claimed": "implemented",
        "checks": [
            (f"{A2A}/A2aClientService.java", [r"class A2aClientService"]),
            (f"{A2A_KERNEL}/A2aRemoteAgentToolSource.java", [r"a2a_call_agent"]),
            (f"{A2A}/A2aRemoteAgentToolCallback.java", [r"A2aRemoteAgentToolSource\.TOOL_NAME"]),
            (f"{A2A}/A2aEgressGuard.java", []),
            (f"{A2A}/A2aCardVerifier.java", []),
            (f"{AGENTS_TOOLS}/AgentIntegrationToolResolver.java", [r"newCallAgentTool"]),
        ],
        "see": [],
    },
    {
        "standard": "A2A",
        "capability": "Push notifications (`tasks/pushNotificationConfig/*`), `tasks/resubscribe`, extended agent card",
        "claimed": "not claimed",
        "checks": [
            (f"{A2A}/A2aJsonRpcController.java", [r"resubscribe"]),
            (f"{A2A}/A2aAgentCardService.java", [r'pushNotifications", true']),
        ],
        "see": [f"{A2A}/A2aAgentCardService.java"],
    },
    # ------------------------------------------------------------ AG-UI ---
    {
        "standard": "AG-UI",
        "capability": "SSE run endpoint `POST /api/agents/{agentId}/agui/run` (RunAgentInput in, `data:`-framed event JSON out)",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiRunController.java",
             [r"/api/agents/\{agentId\}/agui", r'TEXT_EVENT_STREAM_VALUE', r'"/run"']),
            (f"{AGUI}/RunAgentInput.java", []),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Lifecycle events `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR`",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiEvent.java", [r'"RUN_STARTED"', r'"RUN_FINISHED"', r'"RUN_ERROR"']),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Text message events `TEXT_MESSAGE_START` / `CONTENT` / `END`",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiEvent.java",
             [r'"TEXT_MESSAGE_START"', r'"TEXT_MESSAGE_CONTENT"', r'"TEXT_MESSAGE_END"']),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Tool call events `TOOL_CALL_START` / `ARGS` / `END` / `RESULT`",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiEvent.java",
             [r'"TOOL_CALL_START"', r'"TOOL_CALL_ARGS"', r'"TOOL_CALL_END"', r'"TOOL_CALL_RESULT"']),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "`STATE_SNAPSHOT` and `CUSTOM` events (`plan`, `guardrail`, `handoff`)",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiEvent.java", [r'"STATE_SNAPSHOT"', r'"CUSTOM"']),
            (f"{AGUI}/AgUiEventMapper.java", [r'"plan"', r'"guardrail"', r'"handoff"']),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Run event replay/resume `GET .../agui/runs/{runId}/events` (`Last-Event-ID` / `?after=`, durable buffer)",
        "claimed": "implemented",
        "checks": [
            (f"{AGUI}/AgUiRunController.java", [r"Last-Event-ID", r"/runs/\{runId\}/events"]),
            (f"{AGUI}/AgUiRunEventBuffer.java", []),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Agent tool discovery `GET .../agui/tools` (protocol `Tool` shape)",
        "claimed": "not claimed",
        "checks": [
            (f"{AGUI}/AgUiRunController.java", [r'"/tools"', r"record AgUiTool"]),
        ],
        "see": [],
    },
    {
        "standard": "AG-UI",
        "capability": "Binary/protobuf transport",
        "claimed": "not claimed",
        "checks": [
            (f"{AGUI}/AgUiRunController.java", [r"x-protobuf"]),
        ],
        "see": [f"{AGUI}/AgUiRunController.java"],
    },
    {
        "standard": "AG-UI",
        "capability": "Optional event types `STEP_*`, `STATE_DELTA`, `MESSAGES_SNAPSHOT`, `RAW`",
        "claimed": "not claimed",
        "checks": [
            (f"{AGUI}/AgUiEvent.java",
             [r'AgUiEvent\("STATE_DELTA"', r'AgUiEvent\("MESSAGES_SNAPSHOT"', r'AgUiEvent\("STEP_STARTED"']),
        ],
        "see": [f"{AGUI}/AgUiEvent.java"],
    },
    # --------------------------------------------------- OpenTelemetry ----
    {
        "standard": "OpenTelemetry",
        "capability": "OTLP/HTTP trace export (Micrometer `Observation` -> OTel bridge -> `management.otlp.tracing.endpoint`, Langfuse or any collector)",
        "claimed": "implemented",
        "checks": [
            (f"{APPCONFIG}/application.yml",
             [r"otlp:", r"OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"]),
            ("core/app/pom.xml",
             [r"micrometer-tracing-bridge-otel", r"opentelemetry-exporter-otlp"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "`gen_ai.*` attributes on LLM-call spans (`operation.name=chat`, `request.model`, `usage.input/output_tokens`, `usage.reasoning.output_tokens`, `agent.id`, `agent.version`, `conversation.id`, `error.type`)",
        "claimed": "implemented",
        "checks": [
            (f"{CHAT}/GenAiSpanRecorder.java",
             [r"gen_ai\.operation\.name", r"gen_ai\.request\.model", r"gen_ai\.usage\.input_tokens",
              r"gen_ai\.usage\.output_tokens", r"gen_ai\.agent\.id", r"gen_ai\.conversation\.id",
              r"gen_ai\.agent\.version"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "Renamed-attribute migration `gen_ai.system` -> `gen_ai.provider.name` (`app.observability.genai-attrs`: legacy/new/both, semconv v1.37.0)",
        "claimed": "implemented",
        "checks": [
            (f"{CHAT}/GenAiAttributeNames.java", [r"gen_ai\.system", r"gen_ai\.provider\.name"]),
            (f"{APPCONFIG}/application.yml", [r"genai-attrs"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "`invoke_agent` span around agent-attributed calls (chat/execute_tool nest under it)",
        "claimed": "planned",
        "checks": [
            (f"{CHAT}/AgentInvocationObservation.java", [r'"invoke_agent"']),
            (f"{CHAT}/LlmMetricsAdvisor.java", [r"AgentInvocationObservation\.start"]),
            (f"{CHAT}/LlmModelSupport.java", [r"withObservationRegistry"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "`execute_tool` span (`gen_ai.operation.name=execute_tool`, `gen_ai.tool.name`, `gen_ai.tool.type`)",
        "claimed": "planned",
        "checks": [
            (f"{CHAT}/GenAiToolObservationConvention.java",
             [r'"execute_tool"', r"gen_ai\.tool\.name", r"gen_ai\.tool\.type"]),
            (f"{CHAT}/ResilientToolCallingManagerFactory.java", [r"GenAiToolObservationConvention"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "Tail sampling keeps errors + slow `invoke_agent` runs (off by default: `app.observability.tail-sampling.enabled`)",
        "claimed": "not claimed",
        "checks": [
            ("core/app/src/main/java/com/agenticai/core/common/tracing/TailSamplingSpanProcessor.java",
             [r"invoke_agent"]),
            (f"{APPCONFIG}/application.yml", [r"tail-sampling:"]),
        ],
        "see": [],
    },
    {
        "standard": "OpenTelemetry",
        "capability": "Micrometer meters per LLM call (`llm.calls`, `llm.call.errors`, `llm.latency`, `llm.cost.usd`, cache-token counters)",
        "claimed": "not claimed",
        "checks": [
            (f"{CHAT}/LlmMetricsAdvisor.java",
             [r"llm\.calls", r"llm\.call\.errors", r"llm\.latency", r"llm\.cost\.usd"]),
        ],
        "see": [],
    },
]
# fmt: on

STATUS_ORDER = ("implemented", "partial", "planned", "missing")


def check_passes(path: str, patterns: list[str]) -> bool:
    target = REPO / path
    if not target.is_file():
        return False
    text = target.read_text(encoding="utf-8", errors="replace")
    return all(re.search(p, text) for p in patterns)


def evaluate() -> list[dict]:
    rows = []
    for cap in CAPABILITIES:
        checks = cap["checks"]
        passed = [c for c in checks if check_passes(*c)]
        if len(passed) == len(checks):
            status = "implemented"
        elif passed:
            status = "partial"
        else:
            status = "planned" if cap["claimed"] == "planned" else "missing"
        evidence = list(dict.fromkeys([p for p, _ in checks] + cap["see"]))
        rows.append({**cap, "status": status, "passed": len(passed), "evidence": evidence})
    return rows


def render(rows: list[dict]) -> str:
    out = []
    w = out.append
    w("# Standards conformance")
    w("")
    w("<!-- GENERATED by scripts/ci/conformance-report.py from grep-style evidence checks")
    w("     over the source tree. Do not hand-edit; re-run the script (it also has --check")
    w("     for CI). -->")
    w("")
    w("ROADMAP.md section 4 asks for this table to be generated rather than written by")
    w("hand. Every row's status is computed by scanning the listed files for evidence -")
    w("see the *Evidence model* section at the bottom for exactly what a check proves.")
    w("")
    w("| Status | Meaning |")
    w("|---|---|")
    w("| implemented | every evidence check passed |")
    w("| partial | some evidence checks passed |")
    w("| planned | no evidence yet; README/ROADMAP say planned |")
    w("| missing | no evidence; nothing claims it exists (includes deliberate gaps) |")
    w("")
    w("| Standard | Capability | Status | Evidence |")
    w("|---|---|---|---|")
    for row in rows:
        ev = "<br>".join(f"`{p}`" for p in row["evidence"])
        w(f"| {row['standard']} | {row['capability']} | {row['status']} | {ev} |")
    w("")
    # Drift: what the prose claims vs what the tree proves. A stale "planned" in
    # README/ROADMAP once a feature ships (or a "done" that lost its code) is
    # exactly what this table exists to surface.
    drift = [r for r in rows if r["claimed"] != "not claimed" and r["claimed"] != r["status"]]
    w("## Drift vs README.md / ROADMAP.md")
    w("")
    if drift:
        w("Rows where the committed prose claims a different status than the source tree")
        w("proves. Fix the prose, or remove the capability:")
        w("")
        w("| Standard | Capability | Claimed | Detected |")
        w("|---|---|---|---|")
        for row in drift:
            w(f"| {row['standard']} | {row['capability']} | {row['claimed']} | {row['status']} |")
    else:
        w("None - every claimed status matches what the source tree proves.")
    w("")
    w("## Evidence model")
    w("")
    w("Each row lists the files the generator scanned. A check is a small set of regular")
    w("expressions that must all match inside its file (a bare path is an existence")
    w("check). `implemented` means every check in the row passed - code that is wired,")
    w("not merely present; `partial` means some did; `missing`/`planned` mean none did.")
    w("The checks are intentionally structural (class/flag/method-name level), not")
    w("behavioural: this table says *the capability exists in the tree*, and the")
    w("unit/integration tests are what prove it works. Regenerate after touching any")
    w("listed file:")
    w("")
    w("```bash")
    w("python3 scripts/ci/conformance-report.py          # rewrite this page")
    w("python3 scripts/ci/conformance-report.py --check  # CI gate: fail if stale")
    w("```")
    w("")
    counts = {}
    for row in rows:
        counts.setdefault(row["standard"], {s: 0 for s in STATUS_ORDER})
        counts[row["standard"]][row["status"]] += 1
    w("| Standard | Rows | implemented | partial | planned | missing |")
    w("|---|---:|---:|---:|---:|---:|")
    for std in counts:
        c = counts[std]
        w(f"| {std} | {sum(c.values())} | {c['implemented']} | {c['partial']} "
          f"| {c['planned']} | {c['missing']} |")
    w("")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="fail if the committed doc differs")
    ap.add_argument("--stdout", action="store_true")
    args = ap.parse_args()

    rows = evaluate()
    doc = render(rows)

    if args.stdout:
        sys.stdout.write(doc)
        return 0
    if args.check:
        current = OUT.read_text(encoding="utf-8").replace("\r\n", "\n") if OUT.exists() else ""
        if current != doc:
            sys.stderr.write(f"{OUT.relative_to(REPO)} is stale - re-run scripts/ci/conformance-report.py\n")
            return 1
        print("conformance table up to date")
        return 0
    # open(newline=) rather than Path.write_text(newline=) so the script also
    # runs on the Python 3.9/3.10 some dev machines still ship (CI is 3.12).
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(doc)
    counts = {std: sum(1 for r in rows if r["standard"] == std) for std in dict.fromkeys(r["standard"] for r in rows)}
    summary = ", ".join(f"{std} {n}" for std, n in counts.items())
    print(f"wrote {OUT.relative_to(REPO)} ({summary} rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
