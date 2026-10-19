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
APPROVAL is part of the SPI; the approval gate does not call it yet. The executor is built by hand, so it reaches the
journal through `TurnJournalGateway`, installed by `TurnJournalConfig`. Disabled, `begin()` returns a no-op recorder:
no wrapping of tools, no extra operators, no writes. The recorder captures `TenantScope` on the request thread and
re-applies it on whichever thread writes; a failing journal is logged and never reaches the turn.

### Reading a turn

`GET /api/agents/{agentId}/turns/{turnId}/journal` returns the events plus `chainIntact`, `brokenAtSeq`. Authorization:
`PERM_VIEW_AUDIT_LOG` (explicit rule in `AdminIntegrationRules`, one line added to the authorization snapshot) and
VIEWER on the agent. The read works whether or not the flag is on (it serves what was recorded).

### Replay

`TurnReplay` runs a turn against a stubbed `ReplayModel` through the same tool decorator and journal, and
`sameBehaviour(recorded, replayed)` compares the tool-call sequence (name plus argument hash) and the final-answer hash.
Because the journal stores hashes, replay needs the stub (or a fixture) to supply the decisions; the journal supplies the
expected sequence.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `app.turn-journal.enabled` | `false` | write the journal for chat turns on the streaming FAST path |

## Consequences

- Writes are synchronous JDBC from the thread that finishes each stage. This is acceptable behind a flag; before enabling by
  default, measure with the 32-user kind benchmark and move writes to a bounded queue (tenant scope captured) if needed.
- Only `streamFast` is journaled. Buffered depths (plan, verify, delegate) and workflow runs are later slices.
- The chain is per turn. Chaining turn heads into the Ed25519 audit seals (the bet's acceptance) is not done yet.
- A privileged database user can rewrite a whole turn consistently (recompute all hashes); sealing the head hash is what
  closes that.

## Not in this slice

Seal integration, APPROVAL calls from the approval gate, journaling of buffered depths, CLI, UI inspector, retention job.
