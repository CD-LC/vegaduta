# rl-trainer

Offline **preference & prompt optimization** sidecar for the VegaDūta /
agentic-ai-platform. It is the offline optimizer that the Java `core/rl` module
talks to: `core/rl` exports training data (rewards, human feedback, edited
answers, preference pairs) and consumes the improved artifacts this service
produces.

Like `sidecar` (knowledge/RAG) and `code-intel` (codebase intelligence), this
is a **standalone Python FastAPI service**, not part of `core`. Same house
conventions: pydantic-from-env settings, a `/health` endpoint, and the
internal-token / Keycloak auth pattern (`app/auth.py`).

It is **LLM-driven only**. The flagship features call the Anthropic API; there
are intentionally **no torch/GPU dependencies** in the default image. Weight
training (DPO/GRPO) is a documented stub with a clear enablement path — see
[GPU-enablement path](#gpu-enablement-path).

## The two tracks

| Track | What it does | GPU? | Status |
|-------|--------------|------|--------|
| **Track B — offline preference & prompt optimization** | Treat the system prompt as the parameter and optimise it with an LLM (OPRO / textual-gradient / reflection-on-failures); score responses with a Constitutional-AI / RLAIF judge. | No | **Flagship, fully working** |
| **Weight training (DPO/GRPO)** | Actually fine-tune model weights from preference pairs. | **Yes** | **Documented stub** — validates input and queues a job; does not train. |

## Endpoints

### `GET /health`
Returns `{"status": "ok", "mode": "llm" | "stub-only"}`. `mode` is `llm` when
`ANTHROPIC_API_KEY` is configured (the optimizer/judge are usable) and
`stub-only` when it is not (only `/train/dpo` validation works).

### `POST /optimize/prompt` — flagship, fully working
Automated prompt optimization, LLM-driven (no GPU).

Request:
```json
{
  "agentId": "agent-123",
  "currentSystemPrompt": "You are a support agent...",
  "failingCases": [
    {
      "userMessage": "How do I get a refund?",
      "response": "I can't help with that.",
      "reward": 0.1,
      "feedbackComment": "Unhelpful; refunds are supported.",
      "editedAnswer": "You can request a refund from Settings > Billing..."
    }
  ],
  "rubric": "A good response is accurate, actionable, and cites the exact UI path."
}
```

Response:
```json
{
  "candidates": [
    {
      "prompt": "<full revised system prompt>",
      "rationale": "<why this fixes the observed failures>",
      "expectedImprovement": "<estimate>"
    }
  ]
}
```

How it works: the service sends the current prompt, the failing cases, and the
optional rubric to Claude (`RL_TRAINER_LLM_MODEL`, default `claude-sonnet-5`)
and asks it to (a) diagnose the recurring failure modes ("textual gradient"),
(b) propose 2–3 improved candidate system prompts with minimal targeted edits,
and (c) return them ranked best-first with a rationale and an expected-
improvement estimate. This is the OPRO / evolutionary / textual-gradient family
of automated prompt optimization — no weight training, no GPU.

### `POST /train/dpo` — documented stub
Validate a DPO preference JSONL and queue a job. **Does not train weights.**

Request:
```json
{ "jsonl": "{\"prompt\":\"...\",\"chosen\":\"...\",\"rejected\":\"...\"}\n{...}" }
```
The `jsonl` body is one JSON object per line, each with non-empty string
fields `prompt`, `chosen`, `rejected`.

Response:
```json
{
  "jobId": "dpo-ab12cd34ef56",
  "status": "queued",
  "pairs": 128,
  "invalidLines": 0,
  "estimatedTokens": 45000,
  "errorsSample": [],
  "note": "Preference data validated and a job was queued, but this default CPU/LLM-only image has NO GPU training worker... see 'GPU-enablement path'."
}
```
The service validates the JSONL, estimates pairs and token volume, and returns
a queued job. It **does not fake training** — the job stays `queued` until a
GPU worker is attached.

### `POST /judge/constitutional` — RLAIF reward source
Constitutional-AI style critique. A reusable AI-feedback reward source for
`core/rl`.

Request:
```json
{
  "response": "<agent response to evaluate>",
  "principles": ["Be honest about uncertainty.", "Never invent policy details."]
}
```

Response:
```json
{
  "score": 0.42,
  "violations": [
    {"principle": "Never invent policy details.", "explanation": "Claimed a 30-day window that does not exist."}
  ]
}
```

## How `core/rl` feeds this service

`core/rl` (built in parallel, Java) is the data-plane owner; rl-trainer is the
offline compute. The loop:

1. **Export → `/optimize/prompt`.** `core/rl` collects an agent's low-reward /
   negatively-rated / human-edited turns and posts them as `failingCases` with
   the agent's `currentSystemPrompt` and an optional `rubric`. It stores the
   returned ranked candidate prompts as new artifacts for review / A-B rollout.
2. **Export → `/train/dpo`.** `core/rl` exports preference pairs as JSONL
   (`{"prompt","chosen","rejected"}` per line — e.g. chosen = the human-edited
   or higher-reward answer, rejected = the original) and posts the whole body
   as `jsonl`. Today it gets back a validated, queued job (weight training is
   the stubbed part).
3. **`/judge/constitutional` as a reward source.** During offline scoring,
   `core/rl` can call the constitutional judge to get an AI-feedback reward
   (`score` + `violations`) in place of or alongside human labels (RLAIF).

### Consumed export JSONL schema (`/train/dpo`)

One JSON object per line:
```json
{"prompt": "<the input / conversation prefix>", "chosen": "<preferred completion>", "rejected": "<dispreferred completion>"}
```
Blank lines are ignored. Lines missing any of the three non-empty string
fields are counted in `invalidLines` (with a sample in `errorsSample`); a body
with zero valid pairs is a `400`.

## Reward-hacking safety

The prompt optimizer's own system prompt (see `app/optimizer.py`) explicitly
instructs the LLM to **optimise for genuine task success per the rubric, NOT to
game the judge / reward model**. It is told not to craft prompts that flatter
the judge, exploit its blind spots, or pad responses with rubric keywords to
inflate a score without improving the real answer the end user receives. The
constitutional judge is likewise told to score superficial rubric-keyword
compliance LOW. This matters because the reward signals that drive optimisation
often come from an automated judge — an optimiser left to maximise that signal
naively would learn to hack it, raising the measured reward while lowering
genuine quality.

## GPU-enablement path

The default image is CPU-only and torch-free on purpose (fast build, small
image, no CUDA). To turn `/train/dpo` into a real DPO/GRPO trainer, attach a
GPU worker. Three options:

1. **Optional `[gpu]` extra + TRL `DPOTrainer` (self-hosted GPU).** Install the
   heavy deps (kept out of the default `requirements.txt`):
   ```
   torch>=2.3  transformers>=4.44  trl>=0.9  datasets>=2.20  accelerate>=0.33
   ```
   Then a background worker consumes the queued job and runs, roughly:
   ```python
   from datasets import load_dataset
   from trl import DPOConfig, DPOTrainer
   from transformers import AutoModelForCausalLM, AutoTokenizer

   ds = load_dataset("json", data_files="job.jsonl")["train"]  # prompt/chosen/rejected
   model = AutoModelForCausalLM.from_pretrained(BASE_MODEL)
   tok = AutoTokenizer.from_pretrained(BASE_MODEL)
   trainer = DPOTrainer(model, args=DPOConfig(output_dir="out"),
                        train_dataset=ds, processing_class=tok)
   trainer.train()
   ```
   (GRPO is the same shape with `trl`'s `GRPOTrainer`; VERL is an alternative
   for larger-scale RL.) Build this as a separate `rl-trainer:gpu` image with a
   CUDA base — do not add torch to the default image.

2. **Route to a hosted fine-tune API.** Have the worker submit the validated
   JSONL to a managed preference/fine-tune endpoint and poll for completion,
   updating the job status. No local GPU needed.

3. **Dedicated VERL/TRL cluster.** For serious RLHF/GRPO runs, hand the queued
   job to an external training cluster and treat rl-trainer purely as the
   validation + orchestration front door.

In all three, `/train/dpo`'s contract stays the same — the job simply
transitions past `queued` once a worker is attached.

## Configuration

All via env vars (`app/settings.py`):

| Var | Default | Meaning |
|-----|---------|---------|
| `RL_TRAINER_AUTH_MODE` | `internal` | `internal` \| `keycloak` \| `none` (see `app/auth.py`) |
| `RL_TRAINER_INTERNAL_TOKEN` | — | shared secret for internal-token auth (required in `internal` mode) |
| `RL_TRAINER_KEYCLOAK_ISSUER` | `http://localhost:8180/realms/agentic-ai` | browser-facing issuer validated against `iss` |
| `RL_TRAINER_KEYCLOAK_JWKS_URL` | — | JWKS fetch URL (empty = derive from issuer) |
| `RL_TRAINER_LLM_MODEL` | `claude-sonnet-5` | Anthropic model for the optimizer/judge |
| `RL_TRAINER_LLM_MAX_TOKENS` | `8000` | output-token cap per LLM call |
| `ANTHROPIC_API_KEY` | — | read by the SDK; when absent, LLM endpoints return `503` |
| `RL_TRAINER_DATA_DIR` | `/data` | artifact working dir (named volume in compose) |
| `RL_TRAINER_CORS_ORIGINS` | — | comma-separated browser origins (empty = server-to-server only) |

## Running

```bash
# local dev (no auth, no LLM key -> /train/dpo works, LLM endpoints 503)
RL_TRAINER_AUTH_MODE=none uvicorn app.main:app --port 8095

# in the platform stack (OFF by default): set RL_TRAINER_ENABLED=true and a
# real RL_TRAINER_INTERNAL_TOKEN + ANTHROPIC_API_KEY in deploy/.env, then
docker compose up rl-trainer
```

The service is wired into `deploy/docker-compose.yml` with a profile so it is
**off by default** (mirroring how `code-intel` is gated), enabled by
`RL_TRAINER_ENABLED=true`.
