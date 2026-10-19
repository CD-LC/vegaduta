# Golden-Dataset Eval Suite

Master Roadmap 2026 item #15 (CI golden-dataset eval suite, §B/§C).

## What this is

A prompt-regression harness: a golden dataset of
`(system prompt, user message, rubric)` triples run against a live LLM, then
graded by a second LLM call (an "LLM-as-judge") against each case's rubric.
The original core of the dataset is real agent-template personas from
`web/app/components/agentTemplates.js`; the 2026-08 expansion added
purpose-built short personas covering the capability dimensions the platform
actually sells (see the category taxonomy below). It exists to catch a
regression - a prompt/model change that quietly breaks an instruction a
persona depends on, drops a refusal, or degrades Indic-language replies -
before it ships, independent of whichever tenant/agent database row a given
prompt string happens to live on. See `EvalCase.java`'s and `EvalRunner.java`'s
javadoc for the full reasoning.

### Honest to the runner's mechanics

The harness is **text-in/judge-out only**: one generation call, one judge
call, per case. No live tools, no DB, no channels, no multi-turn. Every case
must be written within that boundary - which is why the `tool-judgment` cases
check that the model *describes* using the right capability (the system
prompt tells it to narrate capability choice), not that it invokes one, and
why `memory-directive` cases check the *acknowledgment shape*, not actual
persistence.

## Category taxonomy

Every case carries a `category` from this closed list (enforced by
`GoldenDatasetTest`, which fails the fast free CI job on an off-taxonomy or
malformed edit - keep this table and the test's `CATEGORY_TAXONOMY` set in
lockstep):

| Category | What it checks |
| --- | --- |
| `persona-adherence` | The model keeps following a persona's behavioral instruction (one question at a time, numbered steps, brevity caps) even when the user pushes against it. |
| `safety-refusal` | Prompt-injection attempts (inline, pasted-content, non-English) don't override the system persona; jailbreak phrasings are refused politely and in-role. |
| `tool-judgment` | Given a system prompt that lists capabilities and asks the model to narrate which it would use, it picks the right one (calendar vs email, web search for live data, human handoff for refunds, knowledge base over guessing). |
| `indic-language` | Hindi / Kannada / Tamil / Hinglish user messages get a correct reply in the user's language and register. |
| `business-terse` | WhatsApp-style terse Indian-business queries ("1kg basmati kitna?", "cod hai kya?") get short, correct, list-grounded answers - the real traffic shape. |
| `grounding` | Arithmetic (GST, splits, discounts), business-day date math, and no-fabrication (no invented balances, prices, or nutrition numbers). |
| `long-context` | A system prompt full of facts (policy, schedule, plan table) is answered without contradiction or invented exceptions. |
| `memory-directive` | A persistent-memory block is actually used on recall, and a "remember X" directive gets a specific echo-back acknowledgment. |

The summary report groups pass rates by category, and each case's OTel span
carries `langfuse.trace.metadata.category`, so a nightly regression shows up
as "safety-refusal: 3/5" rather than a bare failing-case list.

## Why it's a CLI harness, not part of `mvn verify`

This makes two real, billed Anthropic API calls per golden case (one
generation, one judge). That's a genuinely different cost/latency tier than
`core/app`'s fast per-push CI job (`.github/workflows/ci.yml`), which is
explicitly scoped to stay fast and free of external API dependencies. The
suite is invoked explicitly via `exec:java` (see "Running it locally") from
its own workflow, `.github/workflows/eval.yml`, on a nightly schedule and
via manual trigger (`workflow_dispatch`) - never on every push. (Formerly
the standalone `core/eval` module; it now lives in `core/quality` under
`com.agenticai.core.quality.eval` - the classes stay Spring-free and
DB-free by design, only the Maven module boundary changed.)

The harness's *unit tests* (`GoldenDatasetTest`, `EvalRunnerReportTest`)
are the deliberate exception: they make zero network calls and run in
ordinary `mvn test` on `core/quality`, so a broken dataset edit fails fast
and free instead of burning the nightly billed slot.

## PR gate (fast subset, `.github/workflows/eval-pr-gate.yml`)

The full nightly run (~35 cases x 2 calls) is deliberately never a PR-blocking
check - too slow/expensive per PR. But that also meant a prompt/persona/
guardrail regression could merge to `main` and ship before the next nightly
run caught it. `eval-pr-gate.yml` closes that gap cheaply: it runs on PRs that
touch `core/quality/**`, `core/agents/**`, or `core/guardrails/**`, using
`EVAL_CASE_IDS` (see above) to run only 8 hand-picked high-signal cases -
safety-refusal/jailbreak resistance, persona-adherence, and grounding
(no-fabrication/arithmetic) - skipping the slower/lower-regression-risk
categories (`indic-language`, `business-terse`, `tool-judgment`,
`long-context`, `memory-directive`). It reuses the same `ANTHROPIC_API_KEY`
secret and exits non-zero on the same conditions (a rubric fail, a case error,
or a token-budget regression) as the nightly job - see `eval.yml`'s and this
file's exit-code notes above. The nightly workflow's own schedule and full
35-case run are unaffected; this is additive.

## Running it locally

```
export ANTHROPIC_API_KEY=sk-ant-...
mvn -pl quality -am compile exec:java
```

Optional env vars:

- `EVAL_MODEL` (default `claude-sonnet-5`) - which model to grade.
- `EVAL_TOKEN_REGRESSION_THRESHOLD_PCT` (default `25`) - see "Token/cost
  regression check" below.
- `EVAL_CASE_IDS` (default unset = run every case) - comma-separated list of
  `EvalCase.id()` values to run instead of the full dataset. Unset/blank runs
  everything, which is what the nightly workflow always does. This is what
  `.github/workflows/eval-pr-gate.yml` uses to run a small, high-signal
  subset on every PR without paying for all ~35 cases x 2 calls each; an id
  that doesn't exist in `golden-dataset.json` is logged as a warning and
  otherwise ignored rather than failing the run (see `EvalRunner#filterByIds`
  javadoc).
- `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` / `LANGFUSE_OTEL_AUTH_HEADER` - trace
  export (see `EvalRunner`'s javadoc; unset auth header = no-op tracing, the
  run itself is unaffected). The same auth header also switches on the
  Langfuse experiment mirror below.
- `EVAL_RUN_NAME` (default `<model>-<sha7|local>-<yyyyMMdd-HHmm>`) - the
  Langfuse dataset-run name. Only meaningful when the auth header is set.

If `ANTHROPIC_API_KEY` isn't set, the runner prints a clear SKIPPED message
and exits `0` - it fails open, not closed, so a fork or a contributor without
the secret configured doesn't get a spurious hard failure for a check that
was never able to run. See `EvalRunner.java`'s javadoc for the full
exit-code contract (errors WITH a key present do fail the run).

## Cost expectations

Rough, deliberately conservative envelope - not a measured invoice:

- 35 cases × 2 calls = 70 API calls per run, sequential.
- Cases are intentionally short (`GoldenDatasetTest` enforces a per-case
  prompt-size cap): typically well under ~1.5k input tokens per case across
  both calls, with output capped at 1024 tokens per call.
- On Sonnet-tier pricing that lands in the **tens-of-cents-per-run** range -
  comfortably under $1/run, i.e. under ~$20-30/month at nightly cadence even
  with headroom. If a future edit adds long-context cases with genuinely
  large system prompts, redo this arithmetic before merging.

Keep new cases short. The suite's value comes from breadth of *dimensions*,
not length of *prompts*.

## Adding a golden case

Add an entry to `src/main/resources/golden-dataset.json`:

```json
{
  "id": "short-stable-id",
  "persona": "which persona/template this exercises",
  "category": "one of the taxonomy buckets above",
  "systemPrompt": "the literal system prompt under test",
  "userMessage": "one realistic user turn",
  "rubric": "what a PASSING response must do, phrased as a requirement",
  "feasibility": "FEASIBLE | UNDER_SPECIFIED | INFEASIBLE (optional)"
}
```

`feasibility` says what the RIGHT move was, independently of what the
assistant did. Omit it and the case is `FEASIBLE` - which is what every case
in the dataset is today, so leaving it out changes nothing. Set it to
`UNDER_SPECIFIED` (the request is missing something the assistant cannot
supply) or `INFEASIBLE` (the task cannot be done at all) and the grading
inverts for that case: declining or escalating becomes the PASS and a
confident rubric-shaped answer becomes the failure. The judge's verdict
vocabulary is three-valued to match - `PASS`, `FAIL: <reason>` or
`ABSTAIN: <reason>` - and the judge only reports what the assistant did; the
runner pairs that with `feasibility` to decide pass/fail. See
`EvalOutcome`/`EvalTaskFeasibility` for why a correct "I cannot do this" has
to be a success state rather than a tolerated error.

A correctly-stopped case prints `[STOPPED]` in the report (not `[FAIL]`, and
not a bare `[PASS]`); one that declined a task it could have done prints
`[ABSTAIN]` and fails. When the run contains any abstention or any
non-`FEASIBLE` case the report grows an `--- Abstention ---` block with the
three counts and the mean confidence-targeted score (`+1.0` for the right
call, `0.0` for an unhelpful refusal, `-1.0` for a wrong or over-confident
answer). A run that is entirely feasible and entirely answered prints no such
block, so today's nightly report is unchanged.

Then run `mvn -pl quality -am test` - `GoldenDatasetTest` validates the
JSON parses, the id is unique, all fields are present, the category is in the
taxonomy, and the case stays under the prompt-size cost cap. (A brand-new
category is a two-file change: document it in the taxonomy table above AND
add it to the test's `CATEGORY_TAXONOMY` - by design, so no undocumented
bucket ever appears in the report.)

Rubric-writing rules that keep the judge verdict trustworthy:

- **Narrow and checkable** - "must ask before giving the final answer," not
  "should be a good tutor." A vague rubric produces a noisy judge verdict,
  which erodes trust in the suite the same way an overly aggressive sampled
  quality judge would (see `LlmQualityJudgeService`, core/agents, for the
  sibling reasoning).
- **Honest to the mechanics** - the judge sees only the system prompt, the
  user message, the rubric, and the response. Don't write rubrics that
  reference tool results, earlier turns, or state that doesn't exist.
- **Bonuses are labeled as bonuses** - if something is nice-to-have, say
  "is a bonus, not required" explicitly, or the judge will fail good
  responses for missing it.
- **Deterministic answers stated in the rubric** - for math/date cases, put
  the correct figure in the rubric itself so the judge grades against it
  rather than re-deriving (and possibly re-fumbling) the arithmetic.

## Token/cost regression check

The LLM-as-judge grader has no concept of cost, so a prompt/rubric/model edit
that quietly doubles token usage while still passing every rubric would ship
unnoticed. Each case's combined generation + judge token usage (`EvalResult
totalTokens`, read from Spring AI's `ChatResponse` usage metadata) is compared
against a **baseline**: the last-known-good total for that case id, stored in
`src/main/resources/eval-token-baseline.json` (`{"case-id": 1234, ...}`). If
the current run's total exceeds the baseline by more than
`EVAL_TOKEN_REGRESSION_THRESHOLD_PCT` (default 25%), the case is flagged
`[TOKEN-BUDGET]` in the report and the run exits `1`, same as a quality
failure.

**Mechanism, deliberately the simplest viable version:**

- A case with no entry in the baseline file has nothing to compare against -
  it is not flagged, and the run does not fail. Add an entry once a case has
  a real run to baseline from.
- The baseline is **not auto-updated**. There is no "write back a new
  baseline on a clean run" step - a CI job silently moving its own goalposts
  would make a slow creeping regression invisible. Updating a case's baseline
  after a deliberate, reviewed cost shift (new model, intentionally longer
  rubric, etc.) is a manual edit to `eval-token-baseline.json`, in the same PR
  as the change that caused the shift.
- This is a single flat JSON file, not a history or a time series - one
  number per case id. Good enough for "did this change blow up the cost,"
  not a substitute for real token-usage dashboards/alerting.

## Langfuse experiments (compare runs across prompt/model changes)

When `LANGFUSE_OTEL_AUTH_HEADER` is set - the same variable that turns trace
export on - every run is also recorded as a Langfuse **dataset run**
("experiment") by `LangfuseEvalClient`, using three public-API calls and the
exact base-URL/credential derivation `core/chat`'s `LangfuseScoreClient`
already uses (base = `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` minus
`/api/public/otel/v1/traces`; credential = the Basic auth header verbatim):

| When | Call | What it does |
| --- | --- | --- |
| Run start | `POST /api/public/v2/datasets` | Ensures the `vegaduta-golden` dataset exists (upsert by name; a 409 is treated as "already there"). |
| Run start, per case | `POST /api/public/dataset-items` | Upserts the case as a dataset item with `id = case.id`, `input = {systemPrompt, userMessage}`, `expectedOutput = {rubric}`, `metadata = {persona, category}`. Re-runs never duplicate; a rubric edit flows through on the next run. |
| After each case | `POST /api/public/dataset-run-items` | Adds the case to the run named `EVAL_RUN_NAME`, linked to the OTel trace id the runner exported for that case (the same id it logs). |
| After each case | `POST /api/public/scores` x5 | On that trace: `rubric_pass` (BOOLEAN, judge reason as comment), `outcome` (CATEGORICAL - `CORRECT`, `INCORRECT` or `ABSTAINED_OR_ESCALATED`), `confidence_targeted_score` (NUMERIC, `[-1, +1]`), `total_tokens` (NUMERIC), `token_budget_ok` (BOOLEAN, budget reason as comment). The last two are skipped when token usage was never read (`totalTokens == -1`). Score ids are `<runName>:<caseId>:<name>`, so a re-post upserts. `rubric_pass` is unchanged and still the gate every saved Langfuse view filters on; `outcome` is what tells a correct refusal apart from a correct answer, since both are `rubric_pass = 1`. |

Nothing is posted when the auth header is unset, and any Langfuse error is a
`WARN` that never touches the exit code - the run's pass/fail is the product,
the mirror is observability on top (see `LangfuseEvalClient`'s javadoc for
the fail-open contract and the still-unverified "OTel trace id == Langfuse
trace id" assumption inherited from `LangfuseScoreClient`).

**Compare-runs recipe.** Run the suite twice - once on the current prompt/
model, once on the proposed one - with distinct `EVAL_RUN_NAME`s (the
default name already embeds model + git sha + timestamp, so two local runs
never collide). In Langfuse open *Datasets > vegaduta-golden > Runs*, select
both runs, and read the per-item `rubric_pass` diff: an item that flipped
1 -> 0 is the regression, with the judge's reason in the score comment and
`langfuse.trace.metadata.judgeReason` on the trace. The workflows should set
`EVAL_RUN_NAME=nightly-<sha>` (`eval.yml`) and `pr-<n>-<sha>`
(`eval-pr-gate.yml`) and pass the two Langfuse secrets through - a
follow-up outside this module's files.

Optional second judge (UI-only, no code): Langfuse's managed LLM-as-judge
evaluators can be pointed at this dataset's runs with a prompt over
`{{input}}`, `{{expected_output}}` (the rubric) and `{{output}}`. That gives
an independent grader alongside the in-code one and addresses the "judge and
subject share a model" limitation below.

## Testing a prompt-version regression specifically

Master Roadmap item #5 (prompt-template versioning, `PromptVersion` entity in
core/agents) gives you a history of an agent's past system prompts. To check
whether a proposed prompt edit regresses against the golden dataset relative
to the current or a past version, temporarily point a golden case's
`systemPrompt` at the version you want to test (paste the `PromptVersion` row's
`system_prompt` text in) and re-run under a fresh `EVAL_RUN_NAME`, then diff
the two runs in Langfuse as above. There is still no live DB read wired into
this harness by design (the `com.agenticai.core.quality.eval` classes stay
DB-free even though they now share the `core/quality` module), so getting the
prompt text in is a manual copy-paste step; the diff itself is no longer.

For tenant-authored datasets replayed against a live agent (not this
text-only harness), the console's eval datasets (`core/quality`,
`POST /api/agents/{agentId}/quality/datasets/seed-golden`) can seed a dataset
from this module's `golden-dataset.json` - the user turns and rubrics are
reused, the system prompt is the agent's own.

## Known limitations

- **Single-turn only.** Personas whose contract is inherently multi-turn
  ("wait for the answer, then ask from weak areas") are only checked on their
  first-turn behavior.
- **Judge and subject share a model** (whatever `EVAL_MODEL` names). A
  model-family-wide blind spot can pass both calls; the suite catches prompt
  regressions far more reliably than model regressions.
- **Indic-language cases check language + basic correctness**, not fluency
  or dialect quality - the judge rubrics are written to be decidable
  ("responds in Tamil"), not literary.
- **Structure tests can't grade rubric quality.** `GoldenDatasetTest`
  guarantees the dataset is well-formed, not that a rubric is well-calibrated;
  watch the first few nightly runs after adding cases.

## Self-hosted / on-device path (`EVAL_BACKEND=openai`)

The FAQ-traffic suite answers the question the edge tier (WebLLM tier-1,
Ollama on a self-hosted box) actually has to pass: *is a small model good
enough for grounded FAQ replies, and how slow is it?*

```bash
# Generation on Ollama, judging on a hosted model (recommended split):
EVAL_BACKEND=openai EVAL_BASE_URL=http://localhost:11434 EVAL_MODEL=qwen3:4b \
EVAL_JUDGE_BACKEND=anthropic EVAL_JUDGE_API_KEY=$ANTHROPIC_API_KEY \
EVAL_DATASET=edge-faq-dataset.json \
mvn -pl quality -am compile exec:java -Dexec.mainClass=com.agenticai.core.quality.eval.EvalRunner
```

- `EVAL_DATASET` picks the dataset: `golden-dataset.json` (default),
  `edge-faq-dataset.json`, or an absolute filesystem path.
- `EVAL_BACKEND=openai` + `EVAL_BASE_URL` hit any OpenAI-compatible
  `/v1/chat/completions` (Ollama, vLLM, llama.cpp, a WebLLM proxy).
  `EVAL_API_KEY` defaults to a placeholder (Ollama ignores it).
- `EVAL_JUDGE_*` (`BACKEND`/`BASE_URL`/`API_KEY`/`MODEL`) redirect the judge
  call separately - grading a small model with itself is not a quality
  signal, so point the judge at a hosted model whenever one is available.
- Without `EVAL_BASE_URL` the run **skips green**, same fail-open posture as
  a missing `ANTHROPIC_API_KEY`.
- Every run prints a per-case latency summary (`p50`/`max`, generation+judge)
  - pass rate alone doesn't prove "good enough for FAQ traffic" if replies
  take 40s.
- Non-default datasets never sync to the `vegaduta-golden` Langfuse dataset.

### On-device (WebLLM / browser tiers)

WebLLM runs in-browser, so the same `edge-faq-dataset.json` also feeds a
browser-side harness that drives the real `routeTurn()` edge router:

```bash
cd web && npx playwright test e2e/edge-faq-eval.spec.ts
# report lands in web/test-results/edge-faq-eval.json
```

The runner page (`web/app/eval/edge-faq`) is served by
`web/app/api/eval/edge-faq` reading this dataset, so both harnesses share
one canonical case source. Per case the report records `outcome`
(`handled` = genuinely on-device, `fallback` with its
`HostedFallthroughReason`, `error`), `tier`, `latencyMs`, answer text,
and a heuristic abstention flag for `UNDER_SPECIFIED` cases; the rubric
is carried through for offline judging by the same `EVAL_JUDGE_*` model.
`status: "edge-unavailable"` means no local tier served anything (build
flag off / device incapable) — a measured skip, never a fabricated pass.
A real edge run needs a build with `NEXT_PUBLIC_EDGE_AGENTS=1`.
