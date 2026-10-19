# ADR: Swarm goal verifier for `delegate_parallel`

Status: implemented behind `app.swarm.verifier.enabled` (default false). Date: 2026-10-02. Compiled/tested: NOT yet
(the lead builds serially; the fix round was also statically checked only). Not deployed.

## Context

`delegate_parallel` (`DelegateToTeamToolCallback`) fans a request out to up to 8 specialist agents and returns every
answer to the calling model. Two gaps from the agentic-platform gap analysis:

1. Workers receive a bare question (plus an optional 1-2 sentence context). They do not know the parent goal, the
   expected output shape, or that they should flag uncertainty.
2. Nothing checks the COMBINED output against the parent's original goal. The calling model sees raw answers and may
   miss that a worker returned nothing, answered a different question, or contradicted a sibling.

Existing pieces reused, not duplicated: `RunBudget` (per-turn USD estimate, shared with `ask_agent_*`), the per-call
branch cap (`MAX_BRANCHES`), the step journal (untouched; repaired branches simply get a different brief hash), and
the semantic-guardrail precedent of using the calling turn's own `ChatClient` for an auxiliary classification call.

## Options

1. **LLM verifier (chosen).** One structured-output call judges coverage per sub-task, contradictions and gaps
   against the goal, returning `PASS|PARTIAL|FAIL` plus confidence.
2. **Rule-based checks.** Non-empty answer, status ok, length floor, keyword overlap with the question. Free and
   deterministic, but it cannot tell a fluent wrong-topic answer from a correct one and cannot see goal-level gaps or
   contradictions. We keep the cheap part of it anyway: a branch with status != ok or an empty answer is always
   treated as unanswered, whatever the model says.
3. **Debate / majority vote.** Run each sub-task on several workers and compare. Costs N x the swarm, fits
   answers with a checkable single value, not open-ended specialist work, and does not address the goal-level gap.
   Rejected on cost.

## Decision

Option 1 plus the rule-based floor, entirely behind `app.swarm.verifier.*`, OFF by default.

- **Structured briefs** (`SwarmVerifier#brief`): objective (the parent goal = the turn's user message, capped by
  `brief-chars`), the worker's own sub-task, required output format, constraints, "report uncertainty explicitly".
- **Verification** (`SwarmVerifier#run`): after all workers return or time out, one call receives the goal, each
  sub-task and each result (each capped by `result-chars`). All of it is UNTRUSTED data: placed in `<goal>`,
  `<subtask>` and `<untrusted_worker_result>` blocks, the system prompt states that content inside is never an
  instruction, and any closing delimiter inside the data is neutralised so it cannot break out of its block.
  Output is strict JSON `{coverage, contradictions, missingForGoal, confidence, verdict}` parsed defensively
  (first `{` to last `}`); anything unparsable or an unknown verdict becomes `UNVERIFIED`. A model exception also
  becomes `UNVERIFIED`. Verification never throws and never blocks the tool result.
- **Bounded repair**: on `PARTIAL`/`FAIL`, and while rounds < `max-repair-rounds` (default 1, hard max 3), only the
  unanswered sub-tasks are re-dispatched (at most `MAX_BRANCHES`, at most what `RunBudget.affordableBranches`
  allows) with the verifier's `missingForGoal` text appended to their context as advisory feedback, then the result
  is verified once more. No loop beyond the cap.
- **Result shape**: the tool result gains `verification: {verdict, confidence, gaps, repairRounds[, reason]}`
  only when enabled. With the flag off no `SwarmVerifier` is built, the brief is the raw question, and the tool
  schema, description and result are unchanged (asserted by a test). No always-on prompt tokens.
- **Metric**: Micrometer counter `swarm.verifier.verdict{verdict=...}`.

## Cost model

Per `delegate_parallel` call: +1 verification call (input approx goal + N x (sub-task + result), each capped at
`result-chars`, default 1500 chars; so N=4 is roughly 2-3k input tokens, a few hundred output) and, only when a
repair happens, up to (unanswered sub-tasks) extra worker runs plus one more verification call. Worst case with the
default cap is 2 verification calls + 8 repaired branches. Every verification call is charged to `RunBudget`
(text-length estimate, same as delegates); if the budget is already exceeded the call is skipped and the verdict is
`UNVERIFIED` with reason `run_budget_exceeded`. Repairs are trimmed to what the budget can afford.

## Failure modes

- **Verifier wrong** (false PASS or false FAIL): advisory only, the model still sees all raw results; a false
  FAIL costs one repair round at most.
- **Prompt injection through a worker result**: mitigated by framing and delimiter neutralisation, not eliminated;
  the verifier has no tools of its own and its output is parsed, never executed. A manipulated verdict can at worst
  trigger a bounded repair or a misleading `PASS`.
- **Same-model blind spots**: the verifier may share the workers' biases.
- **Model choice (fix round)**: the verifier call goes through `AgentChatService#economyClientFor(agentId)`, which
  builds the delegating agent's configured economy-model client (cost routing on, BYOK tenants only, via
  `ChatClientResolver#buildEconomyClientOrNull`). Where none applies (cost routing off, no economy model, hosted-pool
  tenant, any resolution failure) it falls back to the turn's own client and so pays full-model price - configure an
  economy model on the agent to get the cheap path.
- **Stalls (fix round)**: each verifier call runs on its own virtual thread with a hard `timeout-seconds` bound
  (default 30, max 120); a stall is cancelled and yields `UNVERIFIED`.
- **Output framing (fix round)**: verifier-derived strings (`missingForGoal`, `contradictions`, coverage text) have
  angle brackets and control characters stripped and whitespace collapsed at parse time, so what reaches the calling
  model (`verification.gaps`, accompanied by a `gapsNote`) and repaired workers' context is plain data. Input
  neutralisation covers opening, closing and whitespace-spaced variants of the delimiter tags.
- **Deterministic failures (fix round)**: branches that failed with "not a member of this team" or "question is
  required" are never re-dispatched (a retry cannot succeed); timeouts and model errors still are. If the budget is
  exhausted mid-repair-round, remaining branches keep their earlier result.
- **Malformed output / model error / budget**: `UNVERIFIED`, result unchanged.
- **Hint leakage**: the repair hint is verifier text derived from untrusted results; it is capped (600 chars) and
  labelled advisory in the worker's context.
- **Latency**: +1 serial model call (+ a repair round) after the slowest worker.

## Not done

- The goal is the turn's user message; a long multi-turn objective is not reconstructed from history.
