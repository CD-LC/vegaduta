# Ep 6 — Guardrails, approvals and the turn journal

**Length** ~5 min · **Level** everyone · **Renders to** `ep06-guardrails.mp4`

Everything in this episode is demonstrated live, not described: a real
workspace rule blocks a real tool call on camera, the tamper-evident turn
journal replays it, a real pending approval is denied, and the workspace's
runtime settings show real effective values.

## Coverage

- `/admin/guardrails` — the Ledger's real Block rule + platform defaults
- `/agents?agentId=…` — the playground: a live knowledge search that the
  rule blocks mid-turn
- `/agents/turns` — the turn inspector replaying the real journaled turn
- `/admin/approvals` — the real pending queue; a real deny with a reason
- `/admin/runtime-settings` — real effective values + change history

## Click path

| Step | Where | What happens |
|---|---|---|
| The rule | `/admin/guardrails` | "No guaranteed-returns claims" — Block regex, platform defaults below it |
| Live trigger | agent playground | `search_knowledge_base` for the CoinVault ad draft → result **blocked** by the rule; the reply narrates it |
| The proof | `/agents/turns` | Inspect turn `fcc3b6c0-…`: INTAKE → MODEL_CALL → 2× TOOL_CALL **blocked** → GUARD → VERIFY → COMMIT, hash chain intact |
| The queue | `/admin/approvals` | 6 real pending tool calls: proposed action, arguments, policy reason |
| The decision | same page | Reason filled → **Deny** → "Rejected — the agent is carrying on" |
| The settings | `/admin/runtime-settings` | 70 settings with In-effect vs Default; Change history + Export/Import overrides |

## The staged data (why it all works)

- **Rule**: "No guaranteed-returns claims" blocks `(guaranteed|sure-fire|risk-free)\s+(returns?|profits?|gains?)` — seeded by `academy/tools/seed-ledger.mjs`.
- **Bait**: `coinvault-ad-submission.md` in the *Ledger Style Guide* collection — advertiser copy containing "guaranteed returns". A knowledge search that returns it is blocked by the rule.
- **Journal**: turn `fcc3b6c0-f3f4-4250-b744-b6498eabd894` on agent `434bc9b9-…` — a real recorded turn with the two blocked calls. (Turn journal needs `APP_TURN_JOURNAL_ENABLED=true` on core.)
- **Approvals**: real pending tool calls from the Assistant agent's earlier supervised runs.

## Mental model

```
rules      → what text may reach the model   (guardrails, every tool result)
approvals  → what actions the agent may take (pending queue, human decides)
journal    → the tamper-evident record of it (hash-chained events per turn)
settings   → workspace-wide defaults for all of the above (+ change history)
```

## After watching

**Ep 7 — Memory & learning** (next in [the curriculum](../INDEX.md)):
what the platform remembers, and what it earns.
