"""rl-trainer microservice - FastAPI surface.

A separate offline optimizer service (like `sidecar` and `code-intel`), NOT
part of core. The Java `core/rl` module exports training data and consumes the
improved artifacts this service returns. Two tracks:

  * Track B (flagship, fully working): LLM-driven prompt optimization
    (POST /optimize/prompt) and a Constitutional-AI / RLAIF judge
    (POST /judge/constitutional). No GPU required.
  * Weight training (documented stub): POST /train/dpo validates a DPO
    preference JSONL and queues a job, but does NOT train weights - that needs
    a GPU worker intentionally out of scope for this CPU image. See README.

Error bodies use the platform's {timestamp, status, error} shape so core's
existing error handling renders them like any other service error.
"""

from __future__ import annotations

import time

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .auth import AuthError, authenticate
from .dpo import DpoValidationError, queue_job
from .judge import critique
from .llm import LlmCallError, LlmUnavailableError
from .optimizer import optimize_prompt
from .settings import SETTINGS
from .tenancy import TenantContext, TenantValidationError

app = FastAPI(title="rl-trainer", version="0.1.0")

# Browser access is not expected (core/rl is the caller); only enabled when
# explicitly configured, mirroring code-intel.
if SETTINGS.cors_origins.strip():
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[o.strip() for o in SETTINGS.cors_origins.split(",")
                       if o.strip()],
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Tenant-Id",
                       "X-Internal-Token"],
    )


# --------------------------------------------------------------------- #
# auth dependency + error mapping

def tenant(request: Request) -> TenantContext:
    return authenticate(dict(request.headers), SETTINGS)


def _error(status: int, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "status": status, "error": message})


@app.exception_handler(AuthError)
async def _auth_error(_req, exc: AuthError):
    return _error(exc.status, exc.message)


@app.exception_handler(TenantValidationError)
async def _tenant_error(_req, exc):
    return _error(400, str(exc))


@app.exception_handler(DpoValidationError)
async def _dpo_error(_req, exc):
    return _error(400, str(exc))


@app.exception_handler(LlmUnavailableError)
async def _llm_unavailable(_req, exc: LlmUnavailableError):
    # 503: the LLM is not wired up (no API key / SDK), a deployment concern -
    # distinct from a call that failed.
    return _error(503, str(exc))


@app.exception_handler(LlmCallError)
async def _llm_call_error(_req, exc: LlmCallError):
    # 502: the upstream LLM call failed (network, rate limit, unparseable).
    return _error(502, str(exc))


# --------------------------------------------------------------------- #
# models

class FailingCase(BaseModel):
    userMessage: str
    response: str
    reward: float | None = None
    feedbackComment: str | None = None
    editedAnswer: str | None = None


class OptimizePromptRequest(BaseModel):
    agentId: str
    currentSystemPrompt: str
    failingCases: list[FailingCase] = Field(min_length=1)
    rubric: str | None = None


class DpoRequest(BaseModel):
    # The full DPO preference JSONL body (one JSON object per line). Passed as
    # a string so core can forward exactly what core/rl exported, unparsed.
    jsonl: str = Field(min_length=1)


class ConstitutionalRequest(BaseModel):
    response: str
    principles: list[str] = Field(min_length=1)


# --------------------------------------------------------------------- #
# routes

@app.get("/health")
def health():
    # mode advertises whether the flagship LLM endpoints are actually usable,
    # so core can surface "optimizer offline" without a trial call.
    mode = "llm" if SETTINGS.llm_api_key_present else "stub-only"
    return {"status": "ok", "mode": mode}


@app.post("/optimize/prompt")
def optimize_prompt_route(body: OptimizePromptRequest,
                          ctx: TenantContext = Depends(tenant)):
    """Flagship: LLM-driven automated prompt optimization (OPRO / textual-
    gradient reflection on failures). Returns 2-3 ranked candidate system
    prompts with rationale + expected improvement. No GPU required."""
    return optimize_prompt(
        SETTINGS,
        agent_id=body.agentId,
        current_system_prompt=body.currentSystemPrompt,
        failing_cases=[c.model_dump() for c in body.failingCases],
        rubric=body.rubric,
    )


@app.post("/train/dpo")
def train_dpo_route(body: DpoRequest, ctx: TenantContext = Depends(tenant)):
    """Documented stub: validate the DPO preference JSONL, estimate
    pairs/tokens, and queue a job. Does NOT train weights - that needs the
    optional GPU worker (see README). Never fakes training."""
    return queue_job(body.jsonl)


@app.post("/judge/constitutional")
def judge_constitutional_route(body: ConstitutionalRequest,
                               ctx: TenantContext = Depends(tenant)):
    """Constitutional-AI / RLAIF critique: score a response in [0,1] against a
    list of principles and list the violations. A reusable AI-feedback reward
    source for core/rl."""
    return critique(SETTINGS, body.response, body.principles)
