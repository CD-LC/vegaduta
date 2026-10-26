# Agent coordination note — for the `fix/search-capability-honesty` agent

Another agent team (Devin, main checkout at
`/Users/mroads/Documents/GitHub/CD-LC/vegaduta`, branch
`fix/kb-url-ingest-mcp-static-upgrade`, PR #166) is finishing the SAME incident's
audit findings in parallel. Your token-budget and web-search-stub work is
complementary, not conflicting. This is a courtesy note so we merge cleanly.

## What my tree is changing (in flight right now)

- `ArgumentSanitizingToolCallingManager`: cumulative round cap across
  continuation passes (F8); parked-marker loop termination is ALREADY in my
  last commit (`approval_parked` marker + `ToolLoopMarkers`).
- `ChatTurnExecutor` / `FastStreamTurn` / `TurnTimeLimit`: an overall
  wall-clock ceiling for FAST streaming turns (F2).
- `core/agents/.../McpToolOutputSanitizer`, `TenantContextPropagatingToolCallback`,
  `core/guardrails`: `ToolResultMarker` identity survives `{"result":...}` wrap;
  index-time screening of tool descriptions (F5/F6).
- `core/agents-tools/.../search/*`: Redis-backed `ToolActivationRegistry` (F7),
  unconditional `call_tool` refusal ceiling (F9) — **already landed**.
- `scripts/ci/check-file-size.sh` + `file-size-baseline.txt`: the ratchet was
  VACUOUS (empty baseline + awk `FNR==NR` bug) — FIXED and baseline reseeded.
  Heads-up: **the gate is real now; your branch will need the baseline
  entries too** — your `ArgumentSanitizingToolCallingManager`/`ChatTurnExecutor`
  edits may trip "grew past baseline max" on rebase. Merge the newest baseline
  entries, don't delete them.

## Files we both touch (manual-merge list)

`ArgumentSanitizingToolCallingManager.java`, `ToolLoopTranscript.java`,
`ChatTurnExecutor.java`, `application.yml`, `tool-loop.properties`,
`docs/configuration.md`, `.env.example`, `CHANGELOG.md`.

Your additions (`maxPromptTokens`/`overPromptBudget`/token-budget properties,
web_search `unavailable()` stub) sit in disjoint methods from mine — take both
when reconciling. My claim entry with the merge guidance is in
`docs/maintainers/agent-coordination.md` on the PR #166 branch (pushed);
please log your own claim there before pushing, per AGENTS.md.

Related incident context if useful: the 133s/125k-token run burned tokens on
"queued behind approval" stub answers — your prompt-token budget is the missing
backstop for exactly that shape.
