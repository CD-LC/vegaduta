"""
B9-PR1 — OpenAI-compatible mock provider stub.

Why this exists: B9-PR1's goal is a full-stack CI boot with ZERO real LLM API
keys. `core`'s only runtime-configurable (no code change, no redeploy)
provider base URL is control_plane.platform_hosted_llm_provider.base_url
(see core/providers/PlatformHostedLlmProvider.java + core/chat's
HostedLlmChatClientFactory, which builds a Spring AI OpenAiApi client
straight from that row's baseUrl/model/apiKey - runtime data, not a
compile-time constant). Every BYOK factory (OpenAIAgentChatClientFactory,
AnthropicAgentChatClientFactory, etc.) hardcodes its vendor's real base URL
with no env override - so this stub cannot be reached by "wiring an env var
into core" as the original B9-PR1 prompt text loosely put it. Instead,
deploy/seed/seed.sql seeds a platform_hosted_llm_provider row whose
base_url points at this container (http://mock-provider:<port>), which is
exactly the same path a real FREE-tier chat call already takes in
production - no core code change, no test-only branch.

Endpoints implement just enough of the OpenAI API surface for:
  - PlatformHostedLlmProviderService#validateKey, which GETs
    {baseUrl}/v1/models and expects 200.
  - HostedLlmChatClientFactory's Spring AI OpenAiChatModel, which POSTs
    {baseUrl}/v1/chat/completions (Spring AI's OpenAiApi always appends its
    own default completionsPath - see that factory's javadoc for the
    "no trailing /v1 in baseUrl" convention this stub's callers already
    follow).

Deliberately NOT implemented: SSE streaming (stream=true just returns the
same non-streaming JSON body - a real streaming client would misparse
this). B9-PR1's own acceptance criteria never exercises chat completions at
all (only GET /api/agents against seeded data) - streaming is explicitly
B9-PR2's job per that PR prompt's own text ("if the stub lacks streaming,
extend it there, in deploy/, not here"). Do not add streaming support in
this PR; extend here only from B9-PR2.
"""
import time
import uuid

from fastapi import FastAPI, Request

app = FastAPI(title="mock-provider", version="0.1.0")

MODEL_ID = "mock-gpt"


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "mock-provider"}


@app.get("/v1/models")
def list_models() -> dict:
    # Shape matches what PlatformHostedLlmProviderService#validateKey checks
    # (a 200 with a body is enough - it does not parse the payload), and
    # what a real OpenAI-compatible /v1/models response looks like, in case
    # a future caller does parse it.
    return {
        "object": "list",
        "data": [
            {"id": MODEL_ID, "object": "model", "created": 0, "owned_by": "mock-provider"},
        ],
    }


@app.post("/v1/chat/completions")
async def chat_completions(request: Request) -> dict:
    body = await request.json()
    model = body.get("model", MODEL_ID)
    messages = body.get("messages", [])
    last_user_message = next(
        (m.get("content", "") for m in reversed(messages) if m.get("role") == "user"),
        "",
    )

    # Fixed, deterministic reply - good enough for any CI check that only
    # needs "a chat completion call succeeds and returns well-formed JSON",
    # not "the model said something specific." Echoes the last user message
    # back so a caller CAN assert something request-specific if it wants to.
    reply = f"mock-provider received: {last_user_message[:200]}" if last_user_message else "mock-provider: no user message"

    return {
        "id": f"chatcmpl-mock-{uuid.uuid4().hex[:24]}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": reply},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }
