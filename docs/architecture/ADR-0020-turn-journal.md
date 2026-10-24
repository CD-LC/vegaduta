# ADR-0020: Turn journal (the agent turn as an append-only, hash-chained event log)

Status: Proposed. First slice implemented behind a flag (`app.turn-journal.enabled`, default `false`).
Origin: bet 1 in [ARCHITECTURE-BETS-2026-10.md](ARCHITECTURE-BETS-2026-10.md).

## Context

An agent turn is observable today only through separate records: `llm_call_log`, tool-call logs, audit entries, step
events, approvals. Debugging, replay, evals, compliance evidence and cost control each reconstruct the turn from a
different source. We want one ordered record per turn that can be rendered, replayed against a stubbed model and
verified for tampering.

## Decision

Add a `TurnJournal` SPI in the agents module (`com.agenticai.core.agents.turnjournal`):

- `TurnEvent(turnId, agentId, seq, type, payloadHash, summary, prevHash, hash, createdAt)`.
- Types: `INTAKE, GUARD, MODEL_CALL, TOOL_CALL, APPROVAL, VERIFY, COMMIT`.
- `hash = SHA-256(prevHash | turnId | agentId | seq | type | payloadHash | summary | createdAtMillis)`; `prevHash` of seq 0
  is 64 zeros. `TurnHashChain.verify` recomputes the chain and reports the first broken seq (edit, removal, insertion,
  reorder).
- Implementations: `InMemoryTurnJournal` (tests, replay) and `JdbcTurnJournal` (Postgres, tenant table
  `turn_journal_event`, migration V498, primary key `(turn_id, seq)`, appends retry on a lost seq race, an `UPDATE` trigger
  makes rows append-only; `DELETE` stays possible for retention and DPDP erasure).

### What is written, and what never is

Payloads are never stored. `payloadHash` is the SHA-256 of the user message (INTAKE), of the tool arguments (TOOL_CALL),
of the final answer (COMMIT). `summary` is a short structural string (`tool=lookup status=ok resultSha=ab12.. ms=40`,
`provider=.. model=.. route=primary`), passed through `TurnRedaction` (emails, bearer tokens, key-like tokens, long digit
runs and long opaque tokens are masked; 240 characters max). Tool results contribute only a 12 character digest.

### Where it hooks in

`ChatTurnExecutor.streamFast` (hook calls only): INTAKE at entry, MODEL_CALL for the primary call, the tools-disabled retry
and the provider fallback, TOOL_CALL through a `JournalingToolCallback` that decorates each tool, GUARD after the outbound
guardrail audit, VERIFY for the fabricated-generation-URL check, COMMIT once per turn (completed, error or cancelled).
APPROVAL is written by `JournalingToolCallback` when a call is held for a human (see "What a tool call's status means" below)
and, on the turn that continues the task, as the human's decision. The executor is built by hand, so it reaches the
journal through `TurnJournalGateway`, installed by `TurnJournalConfig`. Disabled, `begin()` returns a no-op recorder:
no wrapping of tools, no extra operators, no writes. The recorder captures `TenantScope` on the request thread and
re-applies it on whichever thread writes; a failing journal is logged and never reaches the turn.

### Every depth, and sub-agent turns

The streamed depth (`FastStreamTurn`), the buffered depths (`BufferedTurnRunner`: CAREFUL, RIGOROUS, PLANNED) and the turns a
delegated agent or a specialist runs for another agent's tool call (`SubAgentTurn`) all begin a recorder with
`TurnJournals` and write the same stages. A sub-agent turn is its own record (own turn id, own throwaway session); its
INTAKE carries `parent=<turn id>` when the tool call that started it ran inside a journaled turn on the same thread, so a
reader can follow the link from one record to the other. Sub-agent turns also get what a buffered turn gets (inbound
screening, the delegate's own content guardrails, model-level hooks, finish-reason handling and the continuation policy),
see `SubAgentTurn`.

### What a tool call's status means

`JournalingToolCallback` wraps the tools from outside the guard chain, so the status is worked out from what the platform says
about the result, not from the fact that the callback returned: `ok` (a result the platform did not write), `error` (the
callback threw), `blocked` (a guard, dispatch gate, hook, argument policy, circuit breaker, output withholding or the tool
budget answered instead of the tool) and `parked` (held for a human; an `APPROVAL` event with `decision=pending` and the
approval `request=<id>` precedes it). The platform's answers are recognised by `ToolResultMarker` (core/guardrails): the layer
that answers registers its exact text with a typed kind, so a configured wording (the approval-park text is configurable) or
a wrapped one (an MCP refusal is a JSON object) is still recognised, and the old wording prefixes remain a fallback for a
producer that did not register itself. The turn that continues a task after the human decided writes `decision=decided:completed|failed|denied`
with the same request id in its first events.

### Finding a turn

The turn's id is made when the turn's `TurnContext` is built (`TurnContext#turnId`, never taken from the caller). While the
journal is on, the chat response carries it in `X-Journal-Turn-Id` (exposed to browsers by the CORS config), and every event
of the turn also carries a digest of the chat session id (`session_hash`, tenant migration V666; the same digest is in the
INTAKE summary, inside the hash chain). `GET /api/agents/{agentId}/turns/{turnId}/journal` returns the events plus
`chainIntact`, `brokenAtSeq`; `GET /api/agents/{agentId}/turn-journal?sessionId=...&limit=...` lists a session's turns, newest
first, with the status of each (`completed`, `error:...`, `cancelled`, `refused`, `open`). Authorization for both:
`PERM_VIEW_AUDIT_LOG` (explicit rules in `AdminIntegrationRules`, two lines in the authorization snapshot) and VIEWER on the
agent. The reads work whether or not the flag is on (they serve what was recorded). Turns written before V666 have no session
digest and are found by id only.

### Replay

`TurnReplay` runs a turn against a stubbed `ReplayModel` through the same tool decorator and journal, and
`sameBehaviour(recorded, replayed)` compares the tool-call sequence (name plus argument hash) and the final-answer hash.
Because the journal stores hashes, replay needs the stub (or a fixture) to supply the decisions; the journal supplies the
expected sequence.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `app.turn-journal.enabled` | `false` | write the journal for chat turns on every depth and for delegated / specialist turns |
| `app.turn-journal.max-list` | `100` | most turns one by-session listing returns |
| `app.wiring.strict-gateways` | `false` | fail the start (instead of logging an ERROR) when a switched-on feature's static gateway was never installed |

## Consequences

- Writes are synchronous JDBC from the thread that finishes each stage. This is acceptable behind a flag; before enabling by
  default, measure with the 32-user kind benchmark and move writes to a bounded queue (tenant scope captured) if needed.
- Workflow runs and agent-job turns that do not go through `AgentChatService` are not journaled (a job's chat turns are, like
  any turn). The journal is not linked to `llm_call_log` rows by id; the session is the common key.
- A hosted-chain failover runs the turn again on the next provider with the same turn id: the record then holds two attempts
  (two INTAKE events, an `error:` COMMIT for the first, the final COMMIT for the last). `listBySession` reports the status
  of the last COMMIT.
- The Turn Inspector page (ADR-0021) opens a turn by agent id and turn id and, given a session id, lists that session's turns through the by-session endpoint.
- Nothing purges `turn_journal_event` (the table is append-only by trigger and no job deletes from it), so it grows without bound until an operator prunes it.
- `TurnReplay` is exercised by tests only; no production code path calls it.
- The chain is per turn. Chaining turn heads into the Ed25519 audit seals (the bet's acceptance) is not done yet.
- A privileged database user can rewrite a whole turn consistently (recompute all hashes); sealing the head hash is what
  closes that.

## Not in this slice

Seal integration, CLI, retention job.
