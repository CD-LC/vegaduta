# Ep 8 — Agent quality: grading the paper

**Length** ~3.5 min · **Level** intermediate · **Renders to** `ep08-quality.mp4`

Quality scores an agent's *real* conversations turn by turn — so drift
shows up as a number before it shows up as a bad headline.

## Coverage

- `/agents/quality` — Live quality, Datasets & gate, Simulations,
  Reliability, Solutions
- `/agents/turns` — the Turn inspector: replay one turn's full journal

## Click path

| Step | Where | What happens |
|---|---|---|
| Scoreboard | `/agents/quality` | Pick the agent (Editor-in-Chief); Live quality tracks scored turns |
| Golden set | same → "Datasets & gate" | The conversations an agent must handle + the score floor that blocks a bad change |
| Stress test | same → "Simulations" | Scripted scenarios grade every turn before readers see it |
| Replay a turn | `/agents/turns` | Intake → guardrails → model → tools → approvals → commit, hash-chained |

## Mental model

```
LIVE     — scores accumulate per turn (helpfulness, accuracy, tone,
           length, relevance, safety)
GATE     — golden datasets + a pass floor = the ship/no-ship line
SIMULATE — synthetic scenarios for edge cases
INSPECT  — /agents/turns replays the journal, tamper-evident
```

- "No evaluation data yet" is the honest empty state — scores fill in as
  the agent talks.
- Turn IDs come from chat sessions: open a session's turns via
  `/agents/turns?agentId=…&sessionId=…`.

## After watching

**Ep 9 — Operate** (next in [the curriculum](../INDEX.md)): what it did,
what it cost.
