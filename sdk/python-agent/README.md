# VegaDūta Agent SDK for Python

**Governance for AI agents in Python, as a dependency.** Tool risk
classification, human approval, budgets, idempotent effects, content
guardrails, policy hooks, audit and telemetry, wired around the tools you
already have — plus the guarantees a security review, a compliance officer or
an incident post-mortem asks for: a kill switch, tiered autonomy, delegation
mandates that can only narrow, a tamper-evident (hash-chained, signable)
audit trail, durable SQLite stores, tool-shape pinning, governed memory,
cost attribution and policy replay. Framework-free — works on plain and
`async` functions — with adapters for LangChain, OpenAI-style tool calling
and an OpenTelemetry observer.

This is the Python sibling of the Java Agent SDK (`sdk/java`,
`io.github.cd-lc:vegaduta-agent-*`): same contracts, same vocabulary, same audit
format. It is a different thing from `sdk/python` (`vegaduta_sdk`), which is
the generated REST client that *calls* the VegaDūta platform API — this
library runs *inside your own application*. Apache-2.0. Python 3.10+.

> Status: `0.1.0`, not yet on PyPI. Install from source:
> `pip install ./sdk/python-agent` (or `pip install -e ./sdk/python-agent`
> for development).

## Why this exists

LangChain, Pydantic AI, LlamaIndex, the OpenAI Agents SDK and a hand-rolled
tool loop all give you model calls and tool dispatch. None of them answers
what a security review, a compliance officer or an incident post-mortem asks:

- which tools can this agent reach, and what can each one do to the world?
- who approved that refund, and what exactly was approved?
- how much did this run spend, and what stopped it?
- if the turn is retried, does the payment happen twice?
- did a web page the agent read try to re-program it?
- one audit record across every tool call, tagged with the OWASP agentic
  risk (ASI01–ASI10) it relates to.

The SDK answers those with one dependency-free package and one decorator,
and does not require a server, a UI or a database to start (though it
supports durable storage and pluggable sinks for all three).

## 60-second start

```python
from vegaduta_agent import Governance, governed, RiskLabel, AsiRisk

@governed(risk=RiskLabel.HIGH, requires_approval=True, idempotent=True,
          risks={AsiRisk.ASI02})
def refund(order_id: str, cents: int) -> str:
    ...

@governed(risk=RiskLabel.LOW, side_effecting=False, idempotent=True)
def order_status(order_id: str) -> str:
    ...

governance = Governance()
tools = governance.scan(__import__(__name__))   # or governance.wrap(refund)
```

Now, when the model calls `refund`:

1. The dispatch gate and hooks evaluate the call; `CRITICAL` risk and
   `requires_approval=True` record an approval request instead of running —
   the tool returns `{"approvalRequired": true, "approvalId": ...}` for the
   model to relay to the user.
2. `order_status` runs immediately; a repeat of the same call in the same
   run is served from the effect journal (the payment never happens twice).
3. A per-run budget (default: 50 tool calls) stops loops before they spend.
4. Tool output is redacted for credential-looking text, and flagged when a
   fetched page reads like a prompt-injection attempt.
5. Every outcome lands as one `key=value` audit line on the
   `vegaduta.agent.audit` logger and can drive metrics/OTel `execute_tool`
   spans via observers.

Decide the approval from your own UI, a chat command or a ticket:

```python
gate = governance.approval_gate
req = gate.pending(tenant_id="acme")[0]
gate.approve(req.id, "reviewer@corp")   # runs refund(), journals the result
gate.reject(other.id, "cfo", "not this quarter")
```

## The pieces

| Piece | Type | Default |
|---|---|---|
| `@governed` | decorator → `ToolClassification` | `MEDIUM`, side-effecting |
| Dispatch gate | `ToolDispatchGate` protocol | allow-all; policy lives in the decorator |
| Approval | `ApprovalGate` + `ApprovalStore` | in-memory, 24 h TTL |
| Effect journal | `EffectJournal` | in-memory; `SqliteEffectJournal` is stdlib-durable |
| Budget | `BudgetProvider` → `Budget` | 50 tool calls per run |
| Guardrails | `ContentGuardrail` at 4 stages | secret redaction + injection flagging |
| Hooks | `Hook` at 9 `HookPoint`s | none; fail mode CLOSED |
| Audit | `AuditSink` | `key=value` lines to `vegaduta.agent.audit`; `SqliteAuditSink` for durability |
| Observers | `ToolCallObserver` | none; `OtelToolCallObserver` with `[otel]` |
| Mode | `GovernanceMode` | `ENFORCE`; `OBSERVE` audits without stopping |
| Report | `governance_report(run_id, events, approvals)` | Markdown/JSON: per-tool risk, outcomes, approval decisions, OWASP ASI coverage |
| Kill switch | `HaltSwitch` + `HaltGate` | engages a halt on a tool, an agent or everything; `halt_drill` proves it works |
| Autonomy | `AutonomyGate` + `AutonomyRegistry` | L1 observe → L4 act; L2 recommends, L3 needs approval on HIGH, CRITICAL always gated |
| Mandates | `Mandate.derive`, `verify_mandate_chain`, `MandateGate` | delegation that can only narrow, digest-chained across hops |
| Evidence | `HashChainedAuditSink`, `HmacSha256`, `verify_audit_chain`, `evidence_bundle` | tamper-evident audit with optional signature; JSON/Markdown export |
| Provenance | `ProvenanceGate` on `Governance(provenance=...)` | pins each tool's promised surface; ENFORCE refuses a drifted description or schema |
| Memory | `GovernedMemoryStore` | writes pass guardrails, carry provenance; tool-output text is poison-probed |
| Cost | `CostLedger` + `CostObserver`, `showback` | per-run/agent/principal/tool attribution and a markdown report |
| Replay | `policy_replay`, `ShadowGate`, `TraceRecorder`, `replay_trace` | measure a stricter policy before enforcing it; record and re-run tool traces |
| Risk map | `risk_map(module)` | the scan-before-you-integrate report: unclassified tools, approval coverage, lethal-trifecta, ASI exposure, 0-100 score |
| Notify | `slack_message(req)`, `teams_message(req)` | approval payloads for chat review; the HTTP transport is your port |
| Reconcile | `interrupted_keys(journal)` | effects that began but never recorded — the "did the payment run?" check |
| Approvals+ | `FourEyesAuthorizer`, `allowed_deciders`, `sign_receipt`/`verify_receipt` | two-person rule and signed decision receipts |
| Async | `Governance.wrap_async` / `tool.acall(...)` | same pipeline around `async def` tools |

## LangChain

```python
from vegaduta_agent.adapters.langchain import to_langchain_tools

lc_tools = to_langchain_tools(governance.scan(my_tools_module), context=ctx)
# pass to create_react_agent / bind_tools as usual
```

`pip install vegaduta-agent[langchain]`. The pipeline is unchanged — the
adapter only translates the call surface, so governance behaves identically
across frameworks.

## OpenAI-style tool calling

```python
from vegaduta_agent.adapters.openai import openai_tools, dispatch_tool_call

tools = governance.scan(my_tools_module)
resp = client.chat.completions.create(model=..., tools=openai_tools(tools.values()))
for call in resp.choices[0].message.tool_calls or []:
    messages.append({"role": "tool", "tool_call_id": call.id,
                     "content": dispatch_tool_call(call.model_dump(), tools, ctx)})
```

Dependency-free — it works with the OpenAI SDK, LiteLLM, Azure OpenAI,
Ollama's OpenAI mode or a hand-rolled loop. The governed result (including
`approvalRequired` JSON) is the tool message the model sees.

## Durability and evidence

Everything is in-memory by default so the SDK starts with zero
infrastructure; the stdlib `sqlite3` stores make approvals, audit and the
effect journal durable with one file:

```python
from vegaduta_agent import (Governance, SqliteApprovalStore, SqliteAuditSink,
                            SqliteEffectJournal, HashChainedAuditSink, HmacSha256,
                            verify_audit_chain, evidence_bundle)

sink = SqliteAuditSink("agent.db")
chain = HashChainedAuditSink(sink, HmacSha256(key))   # tamper-evident + signed
g = Governance(approval_gate=ApprovalGate(SqliteApprovalStore("agent.db")),
               journal=SqliteEffectJournal("agent.db"), audit_sink=chain)
...
report = verify_audit_chain(sink.events())           # intact or the break point
bundle = evidence_bundle(sink.events(run_id), run_id).to_markdown()
```

`sign_receipt`/`verify_receipt` do the same for an approval decision: a signed
record of who approved what, verifiable later. HMAC is in the box; plug in
Ed25519 from your crypto library when the audit consumer is untrusted.

## Webhooks and other hooks

`WebhookHook` posts each chosen `HookPoint` to your endpoint — but the
transport is a port you supply (the SDK imports no HTTP client; the contract
test `test_sdk_makes_no_http_call_to_core` enforces it). Three lines of
`urllib.request` or your existing client is the adapter; a response of
`{"block": "reason"}` turns the endpoint into a policy service.

## Observability

`pip install vegaduta-agent[otel]` adds `OtelToolCallObserver`, emitting one
`execute_tool` span per call with `gen_ai.*` attributes — the same convention
the platform's `core/chat` module uses, so platform and embedded agents land
in one trace view.

## Rules that are easy to miss

- **Share the `RunContext` across a run's calls.** Budgets and the effect
  journal are keyed by `run_id`; a fresh context per call defeats both.
  `tool(args, context=ctx)`, or bind one with the LangChain adapter.
- **Approval resumptions are in-memory by default.** An approval that
  outlives the process is recorded but cannot execute; `SqliteApprovalStore`
  makes the request durable (the resumption still must be re-issued — a
  callable cannot persist).
- **Undeclared tools are classified `MEDIUM` + side-effecting**, never
  silently trusted. Declare honestly: `idempotent=True` on a tool that isn't
  is the one dangerous combination.
- **Failures raise, not return.** `ToolDeniedException`,
  `BudgetExceededException`, `GuardrailViolationException` propagate to break
  the tool loop; adapters translate them into model-readable errors.
- **`GovernanceMode.OBSERVE`** is the rollout path: full evaluation and audit,
  nothing blocked. Measure, then enforce.

## Development

```sh
cd sdk/python-agent
python -m venv .venv && .venv/bin/pip install -e '.[dev]'
.venv/bin/python -m pytest tests/ -q
.venv/bin/ruff check vegaduta_agent tests
```
