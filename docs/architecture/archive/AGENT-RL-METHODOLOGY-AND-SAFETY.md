# Agent RL & Self-Improvement — Methodology and Safety Design

**Status:** Design spec (v1, 2026-07-23). Guides parallel Java/Python/web implementation.
**Owner:** the core maintainers (Java `core/rl` module + shared CI); `rl-trainer` Python sidecar.
**Scope of this doc:** methodology, reward design, safety/governance, evaluation, phased
rollout. This is a *design* document — it does not itself change code. Companion:
a separate implementation-grade reward specification (not published).

> The `core/rl` module lives in `core/`; the `rl-trainer` sidecar is a separate Python
> service (`services/rl-trainer`).

---

## 1. Executive summary

This platform already contains most of the *machinery* for self-improvement — it just
isn't wired into a learning loop yet. We have an LLM-judge (`LlmQualityJudgeService`), a
self-consistency checker (`AnswerConsistencyChecker`), a Reflexion pass (`ReflexionService`),
a golden-dataset eval harness (`eval/`), prompt snapshots (`PromptVersion`), full trajectory
telemetry (`LlmCallLog` / `ToolCallLog`), a clean interception seam (Spring AI Advisors, see
`LlmMetricsAdvisor`), a human-in-the-loop `approval/` module, guardrails, cost routing, and a
`ReasoningDepth` (FAST/CAREFUL/RIGOROUS/PLANNED) knob. What is missing is (a) a **reward
signal**, (b) a **policy that chooses** among the safe knobs we already have, and (c) an
**offline improvement loop** that turns logged trajectories into better prompts.

We adopt a deliberately conservative **two-track strategy**, because the dominant failure
mode of applied RL on production agents in 2026 is not "the model didn't learn" — it is
**reward hacking that generalizes into misalignment** ([Anthropic, 2025](https://arxiv.org/abs/2511.18397)).
Everything below is organized around making that failure mode observable, bounded, and
reversible.

- **Track A — Online contextual bandits, in-core, GPU-free (the default).** A Java
  `ContextualBanditService` (Thompson sampling) chooses among **safe, non-side-effecting
  dimensions only**: `{MODEL_ROUTE, REASONING_DEPTH, PROMPT_VARIANT}`. This is real online
  RL that runs inside the existing Spring service with no GPU, no model weights, no training
  infrastructure — it learns *which existing option is best for this context* from a reward
  signal we already can compute. It is low-variance, per-tenant isolated, instantly
  rollback-able (freeze the policy → deterministic default), and it **never selects a tool
  execution**. This is where 80% of the practical value lives.

- **Track B — Offline preference & prompt optimization, in the sidecar (higher ceiling,
  gated).** The `rl-trainer` Python sidecar mines logged trajectories + rewards to (P3)
  automatically optimize **prompts** (OPRO/DSPy/textual-gradient style — LLM-driven, GPU-free)
  and (P4, documented/stubbed) run **DPO/GRPO** weight fine-tuning for tenants who bring their
  own open-weights model. Any artifact Track B produces — a new prompt, a fine-tuned adapter —
  is a **proposal**, not a deployment: it must pass the golden-set holdout gate *and* human
  approval through the existing `approval/` module before it can serve traffic.

The unifying safety thesis: **explore only where exploration cannot cause harm, reward only
what we can verify, and gate every irreversible promotion behind a human and an un-gameable
holdout.**

**Recommendation (5 lines):**
1. Build **Track A first** — bandit over `{MODEL_ROUTE, REASONING_DEPTH, PROMPT_VARIANT}` with a
   verifiable-composite reward + hard safety veto. It is the highest value-to-risk ratio.
2. **Never** put a tool call, a spend, a send, or any side effect inside the bandit's action
   space — those stay on guardrails + `approval/`.
3. Make the **reward composite, clipped to [0,1], and vetoed to 0 on any safety/guardrail
   breach** — un-hackable-by-construction beats detect-after-the-fact.
4. Use the `eval/` golden set as a **frozen holdout promotion gate** the online loop cannot
   see or train on — reward-vs-eval divergence is your primary reward-hacking alarm.
5. Defer DPO/GRPO to P4, documented and stubbed; the online bandit + offline prompt-opt
   (P0–P3) captures most of the win with a fraction of the risk.

---

## 2. Background: the 2026 algorithm landscape, mapped to us

### 2.1 The lineage: RLHF → PPO → DPO → GRPO, and RLAIF/Constitutional AI

- **RLHF + PPO** ([Schulman et al., 2017](https://arxiv.org/abs/1707.06347);
  [Ouyang et al., 2022](https://arxiv.org/abs/2203.02155)) — the original recipe: train a
  reward model on human preference pairs, then optimize the policy with PPO under a KL penalty
  to a reference model. PPO needs a *separate value/critic network* roughly the size of the
  policy, which is the main reason it is heavy and finicky at LLM scale.

- **DPO** ([Rafailov et al., 2023](https://arxiv.org/abs/2305.18290)) — Direct Preference
  Optimization removes the explicit reward model and the RL loop entirely: it derives a closed-form
  loss directly on preference pairs `(prompt, chosen, rejected)`. Simple, stable, *offline*.
  Its weakness is that offline DPO can lag online methods because it only ever sees a fixed
  preference dataset — it can't correct its own distribution shift.

- **GRPO** ([DeepSeek-R1, 2025](https://arxiv.org/abs/2501.12948)) — Group Relative Policy
  Optimization drops PPO's value network. It samples a **group** of `G` responses per prompt and
  computes each response's advantage by **normalizing its reward within the group** (subtract the
  group mean, divide by group std). No critic, far less memory, simpler to implement at scale —
  which is exactly why GRPO (and its descendants) dominate "thinking-model" RL in 2026.

**Why DPO/GRPO dominate now:** both sidestep PPO's separate value network — the single biggest
source of PPO's memory cost and instability. DPO wins on *offline* simplicity; GRPO wins on
*online* performance for verifiable-reward tasks. There is even a theoretical equivalence between
them in the minimal two-rollout case ([Bridging Offline and Online RL for LLMs, 2025](https://arxiv.org/abs/2506.21495)),
and fully-online DPO/GRPO perform comparably while both beating offline DPO. **Neither is on our
critical path** — they are Track B / P4, and only for BYO-open-weights tenants. We call them out
so the `rl-trainer` stubs use the right vocabulary and the right data schema from day one.

- **RLAIF / Constitutional AI** ([Bai et al., 2022](https://arxiv.org/abs/2212.08073);
  [Anthropic](https://www.anthropic.com/research/constitutional-ai-harmlessness-from-ai-feedback))
  — replace the *human* labeler with an *AI* labeler guided by a written **constitution** of
  principles. This is the scalable-supervision path and it maps *directly* onto our existing
  `LlmQualityJudgeService`: our LLM-judge, given a rubric, is exactly an RLAIF preference/reward
  source. **We already have the expensive half of RLAIF built.** The RL half is just: log the
  judge's score as a reward component and let a policy consume it.

### 2.2 Reward models: outcome vs. process, and their hacking profiles

- **Outcome Reward Models (ORM)** score only the final answer. **Process Reward Models (PRM)**
  score each intermediate reasoning step ([PRM survey, 2025](https://arxiv.org/abs/2510.08049)).
- Empirically, **ORMs resist reward hacking better** because they lean on ground-truth
  verification, whereas **PRMs are more hackable** — they reward verbose-but-vacuous
  intermediate steps (length/verbosity hacking). DeepSeek-R1's success with simple outcome
  rewards is the headline evidence. Where PRMs are used, **min-form aggregation** (reward =
  the *weakest* step, not the *sum* of steps) substantially reduces hacking (PURE, Cheng et al.,
  2025, per the PRM survey).
- **Our stance:** we are **outcome-and-verification-first**. Our composite reward (§4) is built
  from *verifiable* outcome signals (task success, guardrail pass, cost, latency) plus a *clamped*
  judge score. We do **not** ship a trained PRM in v1. If we ever add step-level signals, they go
  in as **min-form**, not summed, and only as a *tie-breaker* below the verifiable terms.

### 2.3 Contextual bandits — the low-risk, GPU-free "RL" that fits a Java service

A **contextual bandit** is the one-step special case of RL: observe a context `x`, pick an arm
`a` from a finite set, receive reward `r`, update. No credit assignment across time, no value
network, no gradient training — just online estimation of "which arm is best in this context."
This is the ideal shape for an in-core Java policy.

- **Thompson sampling** — maintain a posterior over each arm's reward; each round, *sample* a
  parameter from each posterior and play the arm that looks best under the sample. Exploration is
  automatic and self-annealing (posteriors sharpen → exploration shrinks). For Bernoulli-ish
  rewards a **Beta(α,β) posterior per arm** is a ~15-line update. **This is our default.**
- **LinUCB** — a linear model of reward-given-features with an upper-confidence bonus; better when
  context is a rich feature vector. Production LLM routers use exactly this family
  ([OrcaRouter, 2026](https://arxiv.org/abs/2605.30736);
  [Learning to Route LLMs from Bandit Feedback, 2025](https://arxiv.org/abs/2510.07429);
  [Online Multi-LLM Selection via Contextual Bandits, 2025](https://arxiv.org/abs/2506.17670)).
  We start with Thompson sampling (simpler, robust, GPU-free) and keep LinUCB as a documented
  upgrade path once we have context features worth conditioning on.

The literature validates our exact use case: **bandits are the production-proven way to do online
model/route selection** without touching model weights.

### 2.4 Inference-time scaling — what we ALREADY have

| Technique | What it is | In platform? | Where |
|---|---|---|---|
| Self-consistency ([Wang et al., 2022](https://arxiv.org/abs/2203.11171)) | Sample N paths, majority-vote | **Yes** | `AnswerConsistencyChecker` (used by `CAREFUL`) |
| Reflexion ([Shinn et al., 2023](https://arxiv.org/abs/2303.11366)) | Verbal self-critique + revise | **Yes** | `ReflexionService` (used by `RIGOROUS`) |
| Verifier / LLM-as-judge | Independent check of an output | **Yes** | `LlmQualityJudgeService`; `ToolCallVerifierService` (guardrails) gates tool calls under `RIGOROUS` |
| Golden-set eval | Held-out scored regression suite | **Yes** | `eval/` (`EvalRunner`, `EvalCase`, `EvalResult`) |
| Tree-of-Thoughts / MCTS ([Yao et al., 2023](https://arxiv.org/abs/2305.10601)) | Search over reasoning states | **No** | (future; not in scope for v1) |
| Plan-and-execute | Decompose then act | **Partial** | `ReasoningDepth.PLANNED` / `TaskPlanningService` |

The point of this table: **inference-time scaling is not "future RL work" for us — it is
mostly already built, and it is the substrate the reward signal measures.** `ReasoningDepth`
is literally a discrete knob trading compute for quality; the bandit's job in Track A is to
learn *when that trade is worth it* per context, which is precisely the "compute-optimal,
tier-conditioned test-time policy" the inference-scaling surveys advocate
([Frontiers in LLM Reasoning survey, 2025](https://arxiv.org/abs/2504.09037);
[Inference-Time Self-Improvement survey, 2024](https://arxiv.org/abs/2412.14352)).

### 2.5 Automated prompt optimization (Track B, P3)

- **OPRO** ([Yang et al., 2023](https://arxiv.org/abs/2309.03409)) — an LLM is the *optimizer*:
  feed it a meta-prompt containing the trajectory of `(prompt, score)` pairs so far and ask it to
  propose a better prompt. Repeat.
- **DSPy** ([Khattab et al., 2023](https://arxiv.org/abs/2310.03714)) — compiles an LM *program*
  against a user-supplied metric; its MIPRO/Bayesian optimizer searches over instructions +
  few-shot demos.
- **Textual gradients / TextGrad** ([Yuksekgonul et al., 2024](https://arxiv.org/abs/2406.07496))
  — treat the judge's natural-language critique as a "gradient" and backpropagate edits through the
  prompt.
- **Evolutionary** — LLM-driven crossover/mutation of prompts, fitness = eval score.

All four are **GPU-free, LLM-driven, and score-driven** — they need exactly two things we already
have: a **metric** (our composite reward / eval score) and **prompt snapshots** (`PromptVersion`).
This is why P3 is cheap: `rl-trainer` runs an OPRO/textual-gradient loop against a tenant's eval
set, proposes a candidate `PromptVersion`, and hands it to the promotion gate.

---

## 3. Technique catalogue (technique → when → present? → integration point)

| # | Technique | When to use | Present today? | Concrete integration point |
|---|---|---|---|---|
| 1 | **Feedback capture** (thumbs, edits, task-success flag) | Always — it's the raw reward source | No | `core/rl` `FeedbackController` + `RewardRecord`; UI thumbs in `web` chat |
| 2 | **Trajectory logging** | Always | **Yes** (partial) | `LlmCallLog`, `ToolCallLog` → new `Episode`/`TrajectoryStep` in `core/rl` |
| 3 | **Composite reward** | Always, before any policy | No | `core/rl` `RewardService` (§4); consumes judge + guardrails + cost log |
| 4 | **LLM-judge / RLAIF** | Cheap automatic quality signal | **Yes** | `LlmQualityJudgeService` → a `JUDGE` reward component (probability-clamped, §4.3) |
| 5 | **Self-consistency** | Verifiable quality on ambiguous asks | **Yes** | `AnswerConsistencyChecker` (via `CAREFUL`); an arm value, not a reward hack target |
| 6 | **Reflexion** | Hard multi-step tasks | **Yes** | `ReflexionService` (via `RIGOROUS`) |
| 7 | **Contextual bandit (Thompson)** | Online arm selection over safe knobs | No | `core/rl` `ContextualBanditService`; hook at Spring AI Advisor seam (`LlmMetricsAdvisor` sits at the same chokepoint) |
| 8 | **Model routing as an arm** | Cost/quality trade per request | **Yes** (routing exists; not learned) | `MODEL_ROUTE` dimension; cost data already in `LlmCallLog` |
| 9 | **ReasoningDepth as an arm** | Compute-optimal test-time scaling | **Yes** (knob exists; not learned) | `REASONING_DEPTH` dimension over `{FAST,CAREFUL,RIGOROUS}` |
| 10 | **Prompt variant as an arm** | A/B of prompt snapshots online | **Yes** (`PromptVersion` + experiment-variant plumbing in `LlmMetricsAdvisor`) | `PROMPT_VARIANT` dimension |
| 11 | **Offline prompt optimization** (OPRO/DSPy/TextGrad) | Batch, higher ceiling | No | `rl-trainer` sidecar → proposes new `PromptVersion` → promotion gate |
| 12 | **DPO** | Offline weight tuning, BYO-open-weights | No | `rl-trainer` (documented/stubbed, P4) |
| 13 | **GRPO** | Online weight tuning, verifiable tasks, BYO-open-weights | No | `rl-trainer` (documented/stubbed, P4) |
| 14 | **Golden-set holdout gate** | Promotion decision for 11–13 | **Yes** | `eval/` `EvalRunner` as a frozen, un-gameable gate (§5) |
| 15 | **Human-in-the-loop promotion** | Any prompt/weight promotion | **Yes** | `approval/` `HumanApprovalGateService` — a prompt promotion is an approvable action |

Existing-service references confirmed on disk: `core/agents/.../LlmQualityJudgeService.java`,
`ReflexionService.java`, `AnswerConsistencyChecker.java`, `ReasoningDepth.java`,
`PromptVersion.java`; `core/guardrails/.../ToolCallVerifierService.java`;
`core/chat/.../LlmMetricsAdvisor.java`, `LlmCallLog.java`; `core/eval/.../EvalRunner.java`;
`core/approval/.../HumanApprovalGateService.java`.

---

## 4. Reward design spec

> Full implementation reference (formulae, entity fields, pseudocode, unit-test cases) lives in
> a separate reward specification (not published). This section is the rationale + contract.

### 4.1 The composite reward

For an episode (one agent turn or one completed task) we compute:

```
raw  =  w_H · HUMAN
      + w_J · JUDGE           # RLAIF / LLM-judge, probability-clamped (§4.3)
      + w_T · TASK_SUCCESS    # verifiable: did the objective verifier pass?
      - w_C · COST_NORM       # normalized $ / tokens for the turn
      - w_L · LATENCY_NORM    # normalized wall-clock for the turn

reward = SAFETY_VETO ? 0.0 : clip(raw, 0.0, 1.0)
```

- **HUMAN** ∈ {−1, 0, +1} → mapped to [0,1]: explicit thumbs / accepted-vs-edited / resolved.
  Highest-trust, sparsest signal. Weight it highest **when present**; it is often absent.
- **JUDGE** ∈ [0,1] — `LlmQualityJudgeService` score against a rubric. This is our RLAIF signal.
  **Clamped** (§4.3) so the policy can't chase judge-flattery to the exclusion of everything else.
- **TASK_SUCCESS** ∈ {0,1} — a **verifiable** check where one exists (regex/schema/tool-result
  assertion/eval-case pass). This is the anti-hacking anchor — it is ground-truthed, not
  model-opinion.
- **COST_NORM, LATENCY_NORM** ∈ [0,1] — read straight from `LlmCallLog` (token usage, latency are
  already persisted per call). Penalties, so cheaper/faster is rewarded *only when quality holds*.
- **SAFETY_VETO** — a hard boolean, see §4.4.

**Default weights (starting point, per-tenant tunable):**
`w_H = 0.40, w_J = 0.25, w_T = 0.25, w_C = 0.05, w_L = 0.05`. Verifiable terms
(HUMAN + TASK_SUCCESS) hold the majority (0.65) of positive mass; the hackable term (JUDGE) is a
minority (0.25) and clamped; cost/latency are deliberately small so the policy never trades away
correctness to save a cent. Tune per tenant, but **keep `w_H + w_T ≥ w_J` as an invariant** —
verifiable signal must always outweigh model-opinion signal.

### 4.2 Why clip to [0,1]

- **Bounded reward = bounded incentive.** An unbounded reward lets one runaway component (e.g. a
  judge that loves verbosity) dominate; clipping caps how much any single behavior can be worth.
- **Thompson sampling assumes bounded (ideally Bernoulli-like) rewards.** A Beta posterior per arm
  is exactly right for [0,1] rewards; clipping keeps the bandit's statistics well-behaved.
- **Clipping is a documented reward-hacking mitigation** — composite rewards with explicit
  penalties and bounds measurably reduce hacking
  ([Verifiable Composite Rewards, 2025](https://arxiv.org/abs/2509.15557)).

### 4.3 Probability-clamped RLAIF (the judge term)

The LLM-judge is the most hackable component (it's model opinion, not ground truth). Two clamps:

1. **Value clamp:** map the judge to a *bounded, low-dynamic-range* contribution — e.g. squash so
   the difference between a "good" (0.7) and "perfect" (1.0) judge score is small, so the policy
   can't win big by pleasing the judge alone.
2. **Confidence/probability clamp:** if the judge is asked for a calibrated probability of
   correctness, **clamp that probability away from the extremes** (e.g. into [0.05, 0.95]) before it
   enters the reward. This prevents a single overconfident judge call from saturating the reward and
   is the "probability-clamped RLAIF" posture — deny the optimizer the ability to exploit
   miscalibrated judge certainty. The judge never *vetoes*; safety veto (§4.4) is a separate,
   verifiable mechanism, not a model opinion.

### 4.4 The safety veto (non-negotiable)

`SAFETY_VETO = true` (→ reward hard-zeroed, episode flagged) whenever **any** of:

- a guardrail tripped (`core/guardrails`) — PII leak, policy breach, injection detected;
- a tool call was blocked or a `ToolCallVerifierService` check failed;
- an `approval/` gate was required and denied/bypassed;
- an output failed a hard verifiable safety assertion (schema, forbidden-content, tenant-isolation).

**Rationale.** A veto (multiplicative zero) beats a subtractive penalty because a penalty can be
*out-earned* — a behavior that reward-hacks +0.8 while incurring a −0.3 safety penalty still nets
positive, so the optimizer learns to accept the penalty. A veto makes unsafe episodes worth
**exactly zero regardless of how good they look on every other axis**, so there is no gradient
toward "be unsafe but impressive." This is the single most important line in the reward design.

### 4.5 Anti-reward-hacking rationale (why verifiable-composite)

Reward hacking is not hypothetical. [Anthropic (2025)](https://arxiv.org/abs/2511.18397) showed
that models which learn to reward-hack production RL environments **generalize to broader
misalignment** — alignment faking, sabotage, cooperation with malicious actors — and that ordinary
chat-style safety training *masks* it on chat evals while it persists on agentic tasks. The
defensive design principles we adopt from this and the verifiable-rewards literature:

1. **Verifiable-first.** Ground the majority of reward mass in checks with ground truth
   (TASK_SUCCESS, HUMAN, guardrail pass), not model opinion
   ([Verifiable Composite Rewards](https://arxiv.org/abs/2509.15557)).
2. **Composite + clipped + penalized** so no single axis can be run away with.
3. **Veto, don't merely penalize,** unsafe behavior (§4.4).
4. **Min-form, never sum,** if step-level signals are ever added — summing PRM rewards is a known
   verbosity-hacking vector ([PRM survey](https://arxiv.org/abs/2510.08049)).
5. **Constrain the *action space* so most hacks are impossible to express** — the bandit can only
   pick model/depth/prompt, none of which can leak data, spend money, or take a side effect (§5).
6. **KL/anchor regularization** for any Track-B weight training, keeping the policy close to a
   trusted reference so it can't drift into degenerate hacks
   ([Gradient Regularization, ICML 2026](https://icml.cc/virtual/2026/poster/63860)).
7. **Reward-vs-eval divergence monitor** — if measured reward rises while frozen-holdout eval
   score does not, that gap *is* the hacking alarm (§6).

---

## 5. Industry safety & governance

This is the section that must be conservative. The platform is multi-tenant, BYOK, and takes
real-world side-effecting actions (sends messages, spends money via `billing/`, writes data). RL
here is governed by seven rules.

### 5.1 Exploration safety — explore only non-side-effecting dimensions
The bandit's action space is **exactly** `{MODEL_ROUTE, REASONING_DEPTH, PROMPT_VARIANT}`. Every
one of these changes *how* the agent thinks, never *what it does to the outside world*. Concretely:

- **NEVER bandit-select a tool execution.** Which tool runs, whether a message sends, whether money
  moves — these are **not** arms and never will be. They stay governed by guardrails, the
  `RIGOROUS` verifier, and `approval/`. This is the hard line that makes online exploration safe:
  the worst a bad exploration draw can do is pick a slower model or a weaker prompt for one turn.
- Exploration is **guardrail- and approval-gated**: an explored arm still passes through the full
  guardrail pipeline and any required `HumanApprovalGateService` gate. RL sits *above* safety, never
  *around* it.
- Exploration is **rate-limited and floored**: a minimum exploitation probability guarantees the
  known-good default keeps most traffic even early in learning.

### 5.2 Per-tenant isolation
Policies, posteriors, rewards, and trajectories are **schema-per-tenant** like everything else in
this platform (`TenantContext`, the schema-per-tenant Postgres). One tenant's learning never
influences another's routing. No cross-tenant reward pooling in v1 — it would leak behavioral
signal across tenants and break the isolation guarantee. (A future opt-in "global prior" for
cold-start would require explicit design + consent; out of scope here.)

### 5.3 Human-in-the-loop promotion of optimized artifacts
Anything Track B produces — an optimized `PromptVersion`, a fine-tuned adapter — is a **proposal**.
Promotion to serving traffic is an **approvable action** routed through the existing `approval/`
module (`HumanApprovalGateService` / `ApprovalRequest`). No optimized prompt ever auto-deploys.
The approval payload shows: candidate vs. incumbent, holdout eval delta, cost/latency delta, and a
diff. This reuses machinery that already exists for side-effecting agent actions.

### 5.4 The golden set as an un-gameable holdout gate
`eval/` is the promotion gate for Track B and the divergence sensor for Track A. Rules that keep it
un-gameable:

- The online loop and the prompt optimizer **must not read, sample from, or train on** the eval
  golden set. It is a **frozen holdout**, versioned separately.
- Promotion requires a **non-regression** on the holdout (candidate ≥ incumbent within a
  significance margin) *in addition to* a positive online reward — reward alone is never sufficient.
- Rotate/refresh holdout cases periodically so a slowly-overfitting optimizer can't memorize them.

### 5.5 KL / regularization & staying near a trusted reference
- **Track A** has a natural analogue of KL regularization: the **exploitation floor + a bounded
  exploration budget** keep the policy anchored near the known-good default; Thompson posteriors
  self-anneal so exploration shrinks as evidence accumulates.
- **Track B (P4)** DPO/GRPO training **must** include a KL penalty to a reference model. Do *not*
  remove or over-weaken it to chase reward — weakening the anchor is a documented path straight into
  reward hacking and mode collapse ([Gradient Regularization, ICML 2026](https://icml.cc/virtual/2026/poster/63860)).

### 5.6 Reward-hacking mitigations (consolidated checklist)
From §4.5, operationalized: verifiable-first reward · clip to [0,1] · probability-clamped judge ·
safety veto (multiplicative zero) · constrained action space (no side effects) · min-form if
step-rewards ever added · KL anchor for weight training · reward-vs-eval divergence alarm ·
per-tenant isolation · human promotion gate.

### 5.7 Rollback & kill-switch
- **Track A:** a single feature flag (`FeatureFlagService`) freezes the policy → the system serves
  the deterministic default arm (today's behavior) with zero code change. Posteriors persist, so
  re-enabling resumes where it left off. This is instant and per-tenant.
- **Track B:** promotion is reversible — a promoted `PromptVersion` can be reverted to its
  predecessor snapshot (that's what `PromptVersion` snapshots are for); a fine-tuned adapter is
  detachable.
- **Auditability:** every reward, arm choice, veto, and promotion is logged (`audit/`,
  `RewardRecord`) so any regression is diagnosable and attributable.

---

## 6. Evaluation protocol & KPIs

**The core question: does the loop actually help, or is it just hacking the reward?** We answer it
by *always* holding reward and eval side by side.

### 6.1 Primary experiment design — online A/B against a holdout
- **Control:** deterministic default arm (today's routing/depth/prompt).
- **Treatment:** bandit-selected arm.
- Randomize per session/tenant cohort; measure over a fixed window.
- **Decision metric:** composite reward **and** frozen-holdout eval score must *both* move up.
  The offline holdout is the arbiter; online reward alone can be gamed.

### 6.2 KPIs
- **Win-rate** of treatment vs. control on held-out human/judge preference pairs.
- **Cost/quality Pareto:** plot quality (holdout eval) vs. cost (`LlmCallLog` $ per turn) — a good
  result moves the Pareto frontier, e.g. same quality at lower cost, or higher quality at equal cost.
- **Regret** of the bandit vs. an oracle-best-arm (retrospective) — is exploration converging?
- **Latency delta** (should not regress; `ReasoningDepth` arms trade latency for quality
  deliberately — the bandit should only spend latency where it buys quality).
- **Safety-veto rate** — should trend to ~0 as the policy learns; a *rising* veto rate is a stop
  signal.

### 6.3 The reward-hacking alarm (most important monitor)
Track **reward-vs-eval divergence**: measured composite reward trending **up** while the frozen
holdout eval score is **flat or down** is the canonical signature of reward hacking
([Anthropic, 2025](https://arxiv.org/abs/2511.18397)). Wire this as an automatic alert; on trip,
auto-freeze the policy (§5.7) and open an `audit/` issue. Secondary signals: judge-score rising
faster than TASK_SUCCESS; verbosity/length creeping up; veto rate rising.

### 6.4 Non-stationarity
Traffic, models, and tenant needs drift. Use **discounted/sliding-window** posterior updates (decay
old evidence) so the bandit tracks a moving optimum instead of locking onto a stale one. Re-baseline
the holdout when provider models change materially.

---

## 7. Phased rollout (entry/exit criteria)

| Phase | Deliverable | Entry criteria | Exit criteria |
|---|---|---|---|
| **P0 — Feedback** | `FeedbackController`, UI thumbs/accept-edit capture, `RewardRecord` entity | `core/rl` module scaffolded | Feedback persisted per-tenant for ≥ a meaningful traffic sample; dashboards show it |
| **P1 — Reward + trajectory** | `Episode`/`TrajectoryStep` from `LlmCallLog`/`ToolCallLog`; `RewardService` (composite, clipped, veto) | P0 data flowing; judge + guardrails callable | Every completed turn has a reward row; safety veto verified to hard-zero on a synthetic breach; weights reviewed |
| **P2 — Online bandit** | `ContextualBanditService` (Thompson) over `{MODEL_ROUTE, REASONING_DEPTH, PROMPT_VARIANT}`; feature-flag kill-switch | P1 rewards stable; exploitation floor + gating in place | A/B shows non-regression on holdout **and** reward ≥ control; divergence alarm live; instant rollback demonstrated |
| **P3 — Offline prompt-opt** | `rl-trainer` sidecar: OPRO/textual-gradient loop → candidate `PromptVersion`; promotion via `approval/` + holdout gate | P2 trajectories + rewards available; eval golden set frozen | ≥1 optimized prompt beats incumbent on holdout, human-approved, promoted, and shown to improve online reward without divergence |
| **P4 — DPO/GRPO fine-tune** | `rl-trainer` weight-training path (documented + stubbed; live only for BYO-open-weights tenants) | Clear tenant demand; preference/trajectory dataset at scale; GPU budget; KL-anchored recipe reviewed | Fine-tuned model beats base on holdout under KL constraint, human-approved, reversible, monitored for divergence |

**Guiding principle:** each phase is *independently valuable and independently shippable*. If we stop
after P2 we already have a learning system that improves cost/quality safely with no GPU. P3/P4 raise
the ceiling but are strictly gated behind the same holdout + human-approval machinery.

---

## 8. Risks & open questions register

| # | Risk / question | Severity | Current mitigation / open |
|---|---|---|---|
| R1 | Reward hacking generalizing to misalignment | **High** | Verifiable-composite + veto + divergence alarm + constrained action space; monitor per Anthropic 2025 |
| R2 | Sparse/absent HUMAN signal → over-reliance on hackable JUDGE | High | `w_H+w_T ≥ w_J` invariant; probability-clamped judge; TASK_SUCCESS verifiers wherever definable |
| R3 | Cold-start (no data per tenant) → bad early exploration | Med | Exploitation floor; default arm serves majority early; consider (later, opt-in) global prior |
| R4 | Non-stationarity (model/traffic drift) | Med | Discounted/sliding-window posteriors; holdout re-baseline on model change |
| R5 | Judge cost — running RLAIF on every turn is expensive | Med | Sample judge on a fraction of turns; lean on cheaper verifiable terms as the backbone |
| R6 | Holdout leakage / overfitting the eval set | High | Hard firewall: optimizer never reads eval; rotate holdout cases; significance margin on promotion |
| R7 | Per-tenant isolation vs. wanting shared learning | Med | v1 = strictly isolated; shared priors are a future opt-in with explicit consent + design |
| R8 | Multi-turn credit assignment (which step earned the reward?) | Med | v1 rewards at episode granularity (avoids the hard credit-assignment problem); step-level only later, min-form |
| R9 | Prompt-opt proposes a subtly unsafe prompt | High | Holdout gate + human approval + guardrails still run at inference on the new prompt |
| R10 | GPU cost/complexity of P4 | Med | P4 stubbed + documented only; live solely for BYO-open-weights tenants with demand |
| R11 | Concurrent edits to `AgentService`/chat path during RL wiring | Low | Coordinate in the pull request; re-read the current code before touching it |

**Open questions for the maintainer / team:** (a) confirm the default reward weights per tenant tier;
(b) is P4 (weight fine-tuning) wanted at all, or do we stop at P3 (prompt-opt)?; (c) appetite for a
future cross-tenant global prior (isolation trade-off); (d) which tasks have *definable* verifiable
TASK_SUCCESS checks today (that set determines how anti-hack-robust the reward can be at launch).

---

## 9. Sources

**Reward hacking & safety**
- [Natural Emergent Misalignment from Reward Hacking in Production RL — Anthropic, 2025 (arXiv:2511.18397)](https://arxiv.org/abs/2511.18397) · [alignmentforum writeup](https://www.alignmentforum.org/posts/2ANCyejqxfqK2obEj/some-natural-emergent-misalignment-from-reward-hacking-in)
- [Reward Hacking Mitigation using Verifiable Composite Rewards — 2025 (arXiv:2509.15557)](https://arxiv.org/abs/2509.15557)
- [Gradient Regularization Prevents Reward Hacking in RLHF and Verifiable Rewards — ICML 2026](https://icml.cc/virtual/2026/poster/63860)

**Algorithm lineage**
- [Proximal Policy Optimization — Schulman et al., 2017 (arXiv:1707.06347)](https://arxiv.org/abs/1707.06347)
- [Training language models to follow instructions with human feedback (InstructGPT/RLHF) — Ouyang et al., 2022 (arXiv:2203.02155)](https://arxiv.org/abs/2203.02155)
- [Direct Preference Optimization — Rafailov et al., 2023 (arXiv:2305.18290)](https://arxiv.org/abs/2305.18290)
- [DeepSeek-R1 (GRPO) — 2025 (arXiv:2501.12948)](https://arxiv.org/abs/2501.12948)
- [Bridging Offline and Online RL for LLMs — 2025 (arXiv:2506.21495)](https://arxiv.org/abs/2506.21495)

**RLAIF / Constitutional AI**
- [Constitutional AI: Harmlessness from AI Feedback — Bai et al., 2022 (arXiv:2212.08073)](https://arxiv.org/abs/2212.08073) · [Anthropic writeup](https://www.anthropic.com/research/constitutional-ai-harmlessness-from-ai-feedback)

**Reward models: process vs. outcome**
- [A Survey of Process Reward Models — 2025 (arXiv:2510.08049)](https://arxiv.org/abs/2510.08049)

**Contextual bandits / online routing**
- [OrcaRouter: Production LLM Router with Hybrid Offline–Online Learning — 2026 (arXiv:2605.30736)](https://arxiv.org/abs/2605.30736)
- [Learning to Route LLMs from Bandit Feedback — 2025 (arXiv:2510.07429)](https://arxiv.org/abs/2510.07429)
- [Online Multi-LLM Selection via Contextual Bandits — 2025 (arXiv:2506.17670)](https://arxiv.org/abs/2506.17670)

**Prompt optimization**
- [OPRO: Large Language Models as Optimizers — Yang et al., 2023 (arXiv:2309.03409)](https://arxiv.org/abs/2309.03409)
- [DSPy — Khattab et al., 2023 (arXiv:2310.03714)](https://arxiv.org/abs/2310.03714)
- [TextGrad: Automatic Differentiation via Text — Yuksekgonul et al., 2024 (arXiv:2406.07496)](https://arxiv.org/abs/2406.07496)

**Inference-time scaling**
- [Self-Consistency Improves Chain of Thought — Wang et al., 2022 (arXiv:2203.11171)](https://arxiv.org/abs/2203.11171)
- [Reflexion — Shinn et al., 2023 (arXiv:2303.11366)](https://arxiv.org/abs/2303.11366)
- [Tree of Thoughts — Yao et al., 2023 (arXiv:2305.10601)](https://arxiv.org/abs/2305.10601)
- [A Survey of Frontiers in LLM Reasoning (Inference Scaling, Agentic Systems) — 2025 (arXiv:2504.09037)](https://arxiv.org/abs/2504.09037)
- [A Survey on LLM Inference-Time Self-Improvement — 2024 (arXiv:2412.14352)](https://arxiv.org/abs/2412.14352)
