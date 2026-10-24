# ADR-0021: Turn inspector (read-only console view of the turn journal)

Status: Proposed. First slice implemented (`/agents/turns`).

## Context

ADR-0020 records each agent turn as an append-only, hash-chained event log (INTAKE, GUARD, MODEL_CALL, TOOL_CALL, APPROVAL,
VERIFY, COMMIT) behind `app.turn-journal.enabled` (default `false`) and serves it at
`GET /api/agents/{agentId}/turns/{turnId}/journal`. Nothing in the console showed it. Architecture bet 10 asks for a way to
see what a turn did and to turn a real turn into a regression test.

## Decision

Add a read-only inspector at `/agents/turns` (nav: TEST & MONITOR, "Turn inspector", scope `VIEW_AUDIT_LOG`, the same scope
the endpoint enforces; the agent VIEWER check stays server side).

- Inputs: agent ID and turn ID (UUIDs), also as `?agentId=&turnId=` deep links.
- Timeline: one card per event with type, UTC time, gap from the previous event, the redacted structural summary, and the
  payload / previous / event hashes. Tool events show the `ms=` the tool reported.
- Chain badge: the browser recomputes every hash (`SHA-256(prevHash|turnId|agentId|seq|type|payloadHash|summary|createdAtEpochMillis)`,
  mirroring `TurnHashChain`) and checks sequence and linkage. The browser's verdict is shown; the server's `chainIntact` /
  `verification` is shown beside it. If they disagree the page says so and treats the journal as untrusted. Without Web Crypto
  only the server verdict is shown. This detects edits, removals, insertions and reordering; it does not detect a privileged
  rewrite of a whole turn (that needs the seal integration listed in ADR-0020).
- Cost and latency: the DTO has no cost or token fields. Shown: wall time (first to last event), summed tool `ms=`, model call
  count, and "Not recorded" for cost (a `costMinor=` summary token is summed if a later slice adds one).
- States: idle, loading, invalid IDs, 404 (no journal; explains that the journal is off by default and how to enable it:
  `app.turn-journal.enabled=true` / `APP_TURN_JOURNAL_ENABLED=true` on the core API, then restart; only later turns are recorded),
  403 (permission), other errors with retry.
- No new backend endpoint and no write path.

## Test fixture export

"Export as test fixture" downloads `turn-<turnId>.fixture.json`. Content is exactly what core returned (hashes and redacted
summaries); nothing is un-redacted, and the page never fetches payloads (they are not stored).

```json
{
  "schema": "vegaduta.turn-fixture/1",
  "exportedAt": "2026-10-15T12:00:00.000Z",
  "source": { "agentId": "<uuid>", "turnId": "<uuid>" },
  "chain": { "serverIntact": true, "brokenAtSeq": -1, "verification": "ok", "clientVerified": true },
  "events": [
    { "seq": 0, "type": "INTAKE", "payloadHash": "<sha256>", "summary": "messageChars=12 attachments=0 tools=1",
      "prevHash": "<64 zeros for seq 0>", "hash": "<sha256>", "createdAt": "2026-10-15T10:00:00Z" }
  ],
  "expected": {
    "eventTypes": ["INTAKE", "MODEL_CALL", "TOOL_CALL", "COMMIT"],
    "toolSequence": ["<tool>|<argumentsPayloadHash>"],
    "finalAnswerHash": "<payloadHash of the last COMMIT, or null>",
    "finalStatus": "completed"
  }
}
```

- `clientVerified` is `null` when the browser could not hash.
- `expected` mirrors `TurnReplay.sameBehaviour`: the ordered `tool|argumentsHash` sequence plus the final-answer hash. A testkit
  loader replays the turn against a stubbed model and asserts both equal `expected`. `events` are kept so the loader can re-verify
  the chain and feed the recorded decisions to the stub. `schema` is versioned; additive fields do not bump it.
- Status: this ADR defines the shape and the web exporter. The Agent SDK testkit loader (`sdk/java/agent-testkit`) is not written yet.

## Consequences

- Operators can answer "what did this turn do" without database access, and turn an incident into a fixture.
- The inspector shows only what the journal records. It cannot show prompts or answers by design.
- Not in this slice: listing all turns of an agent (a session's turns are listed through `GET .../turn-journal?sessionId=`), diffing two turns, replay from the UI,
  approval-gate linking, cost and token capture, testkit loader.
