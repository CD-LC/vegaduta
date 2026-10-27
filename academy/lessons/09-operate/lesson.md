# Ep 9 — Operate: cost, health, audit

**Length** ~3 min · **Level** everyone · **Renders to** `ep09-operate.mp4`

The unglamorous screens that answer "what did it do" and "what did it
cost" — with this install's real numbers on screen.

## Coverage

- `/insights` — AI calls, success rate, estimated cost, failure list
- `/usage` — token metering across agents, workflows, quick tasks
- `/troubleshoot` — system status and failure forensics

## Click path

| Step | Where | What happens |
|---|---|---|
| The dashboard | `/insights` | Real counters: 1,802 AI calls / 98% success / $0.00 est. cost this week |
| Range | same | 7 / 30 / 90-day chips reframe the counters; "32 failed" links to the failure list |
| The meter | `/usage` | Token counts per model call; no limit on personal — a reference not a ceiling |
| Forensics | `/troubleshoot` | System status + failed-call trail; the start of every wrong-answer investigation |

## Mental model

- **Insights** = volume + reliability + cost estimate (list-priced).
- **Usage** = the raw meter (tokens, refreshable).
- **Troubleshoot** = where a reported wrong answer gets traced.
- (Personal tier: `/roi` and `/eval` aren't mounted here — see the
  INDEX's enterprise-route note.)

## After watching

**Ep 10 — Ship it** (next in [the curriculum](../INDEX.md)): schedules
and long-running work.
