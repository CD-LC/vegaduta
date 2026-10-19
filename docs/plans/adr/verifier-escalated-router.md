# ADR: Verifier-escalated model router (L4)

Status: first slice implemented behind a flag (default OFF). Date: 2026-10-01.

## Context
`QueryComplexityClassifier` guesses before the call whether a turn may use the agent's economy model.
It never learns from the answer. The gap report asked for a cheap-first cascade that escalates on
verifier signals.

## Decision (stage 1)
`VerifierEscalationRouter` checks the cheap model's answer with deterministic signals (no extra LLM call):
structured-output parse failure (agent `resultSchemaJson` set), explicit low-confidence marker,
tool-error loop (>= `max-tool-errors` errored tool calls this turn), empty reply. On failure the turn is
re-run once on the agent's primary model. The hook is `BufferedTurnRunner.callFirstDraft`, used ONLY for the
first draft of verifyThenRespond / planThenRespond (never the shared `callBufferedTurn`, so re-plan retries do
not re-escalate). After an escalation the consistency checker, Reflexion, outbound guardrails and the re-plan
retry all run on the primary client that wrote the kept answer, not the cheap one. In PLANNED the plan step is
produced before the draft and is never inspected by the answer verifier (only the final answer is). Only turns that
`ChatClientResolver` actually routed to the economy model are eligible (client registered per agent).
Decisions and estimated savings are Micrometer counters. Config: `app.chat.escalation.*`, kill switch
`kill-switch=true`. Flag off = no code path change (router null/inactive short-circuits).

## Options considered
1. Pre-call learned classifier (RouteLLM-style): needs labelled preference data; deferred.
2. LLM-as-judge on every answer: defeats the cost goal.
3. Deterministic post-call verifier (chosen): zero extra spend, inspectable, fail-open.

## Limits / risks
- Buffered depths only (CAREFUL/RIGOROUS/PLANNED). FAST is the main chat path and is NOT covered: it streams
  tokens before any verification is possible, so escalating there needs a buffer-then-decide mode (stage 2, costs
  time-to-first-token). Stage 1 therefore does not satisfy 'in the chat path' for FAST turns.
- Guardrail-flag signal is NOT implemented: `Signals.guardrailFlag` exists and `shouldEscalate` honours it, but
  `inspect` always passes false. The outbound guardrail gate audits/replaces on every block, so pre-checking the
  cheap draft would double-audit. Four spec signals = three live + one deferred (stage 2).
- Re-running tools on escalation can repeat side effects. Guard: escalation is skipped (counter
  `chat.router.escalation_skipped`) when the cheap attempt made any tool call whose name is not read-only by prefix
  (get/list/search/read/fetch/find/lookup/query/...). Unknown names count as mutating. This is a name heuristic,
  not a tool-metadata contract; a real read-only/idempotent flag on tool definitions is the proper fix. The retry
  shares the turn's tool step budget and is fail-open (keeps the cheap answer on any error).
- Savings are estimates from reply length and `economy-cost-ratio`, not billing data.
- Low-confidence phrases are English-only by default; override via `low-confidence-markers`. To limit false
  positives, a reply longer than 600 chars is only scanned in its first 240 chars; shorter replies are scanned
  whole. The real-world false-positive rate is still unmeasured (staged item 4).
- Registration is last-writer-wins per agent and matched by client identity; a mismatch means no escalation.

## Staged plan
2. Guardrail flag wired from outbound guardrail results; escalate-on-flag. Also the FAST buffer-then-decide mode.
3. Feed decisions into the RL bandit (core/rl) as reward so the pre-call classifier is learned.
4. Eval-gate: run golden-dataset cases through cheap vs strong models nightly to calibrate markers.
