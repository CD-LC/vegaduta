# Ep 6 — Guardrails: the editor's red pen

**Length** ~3.5 min · **Level** everyone · **Renders to** `ep06-guardrails.mp4`

Guardrails are patterns the workspace refuses to pass through. Every rule
on `/admin/guardrails` is checked against the result of **every tool call**
before that text reaches the model — untrusted input gets filtered at the
boundary, always.

## Coverage

- `/admin/guardrails` — workspace rules: Block and Redact patterns
- `/agents` — the per-agent Guardrails card (the conversation layer)

## Click path

| Step | Where | What happens |
|---|---|---|
| Workspace rules | `/admin/guardrails` | Real rule on screen: "No guaranteed-returns claims" — a Block regex `(?i)(guaranteed\|sure-fire\|risk-free)…` |
| Where rules apply | same page | **Tool results — always.** Every tool output checked before the model reads it. **Conversations — per agent**, opt-in on the agent's Guardrails card |
| Per-agent layer | `/agents` → agent card → Guardrails | Block works both directions; Redact can rewrite the agent's reply |

## Mental model

```
built-in checks (prompt-injection, secret-shaped strings)   ← always on
workspace rules (this page)                                  ← tool results, always
per-agent rules (Agents → Guardrails card)                   ← conversations, opt-in
```

- Rules are case-insensitive regexes, matched anywhere in the text.
- They **add to** platform checks — they never replace or weaken them.
- The Ledger's real example: never print certainty about markets.

## After watching

**Ep 7 — Memory & learning** (next in [the curriculum](../INDEX.md)):
what the platform remembers, and what it earns.
