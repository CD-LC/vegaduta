"""Constitutional-AI / RLAIF judge - a reusable AI-feedback reward source.

Given a response and a list of principles (the "constitution"), an LLM critiques
the response against each principle and returns a normalised score in [0, 1]
plus the list of violated principles. This is the RLAIF pattern: an LLM acting
as the reward/feedback model in place of (or alongside) human labels. core/rl
can call it as a reward source when scoring agent outputs offline.
"""

from __future__ import annotations

import math

from .llm import complete, extract_json
from .settings import Settings

_JUDGE_SYSTEM = """\
You are a Constitutional-AI critic providing AI feedback (RLAIF). You are given \
a RESPONSE produced by an AI agent and a list of PRINCIPLES (the constitution) \
the response is expected to satisfy. Evaluate the response strictly and \
honestly against each principle.

Judge only what the response actually does; do not reward superficial \
compliance (e.g. merely mentioning a principle's keywords), and do not be \
swayed by confident tone. A response that games the rubric without genuinely \
satisfying it should score LOW.

Respond with ONLY a JSON object of this exact shape (no prose outside it):
{
  "score": <number between 0 and 1, where 1 = fully satisfies every principle>,
  "violations": [
    {"principle": "<the principle text>", "explanation": "<how it is violated>"}
  ]
}
An empty "violations" list means no principle was violated."""


def _clamp01(x) -> float:
    try:
        v = float(x)
    except (TypeError, ValueError):
        return 0.0
    # NaN must fail LOW: json.loads accepts a bare `NaN`, and min(1.0, nan)
    # returns 1.0, so without this guard a NaN score would clamp to a
    # PERFECT reward - exactly the fail-open a reward source must never have.
    if math.isnan(v):
        return 0.0
    return max(0.0, min(1.0, v))


def critique(settings: Settings, response: str, principles: list[str]) -> dict:
    """Run the constitutional critique and return {score, violations}. Raises
    LlmUnavailableError/LlmCallError (mapped to 503/502 in main.py)."""
    user_lines = ["PRINCIPLES:"]
    for i, p in enumerate(principles, 1):
        user_lines.append(f"{i}. {p}")
    user_lines += ["", "RESPONSE TO EVALUATE:", response]

    raw = complete(settings, _JUDGE_SYSTEM, "\n".join(user_lines))
    parsed = extract_json(raw)
    if not isinstance(parsed, dict):
        parsed = {}

    violations = parsed.get("violations", [])
    clean_violations = []
    if isinstance(violations, list):
        for v in violations:
            if isinstance(v, dict):
                clean_violations.append(
                    {
                        "principle": str(v.get("principle", "")),
                        "explanation": str(v.get("explanation", "")),
                    }
                )
            elif isinstance(v, str):
                clean_violations.append({"principle": v, "explanation": ""})

    return {
        "score": _clamp01(parsed.get("score")),
        "violations": clean_violations,
    }
