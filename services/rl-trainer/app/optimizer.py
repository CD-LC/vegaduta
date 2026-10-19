"""Automated prompt optimization - the flagship, fully-working feature.

This is "Track B" offline optimization done the LLM-driven way: instead of
training weights (which needs a GPU - see /train/dpo and the README), we treat
the system prompt itself as the parameter to optimize and use an LLM as the
optimizer. The technique is the textual-gradient / "reflection on failures"
family (OPRO - "Large Language Models as Optimizers"; also called evolutionary
or textual-gradient prompt optimization):

  1. analyse the failing cases (low reward, negative feedback, human edits) to
     infer WHAT the current prompt gets wrong - the "textual gradient",
  2. propose a small set of improved candidate system prompts that address
     those failure modes,
  3. return them ranked with a rationale and an expected-improvement estimate.

Reward-hacking safety: the optimizer's system prompt explicitly instructs it
to optimise for GENUINE task success per the rubric, NOT to game whatever
judge/reward model produced the scores. See the README's "Reward-hacking
safety" note - a prompt that learns to flatter the judge would raise reward
while lowering real quality, which is exactly the failure we forbid here.
"""

from __future__ import annotations

from .llm import complete, extract_json
from .settings import Settings

_OPTIMIZER_SYSTEM = """\
You are an expert prompt optimizer for a production AI agent platform. You are \
given an agent's CURRENT system prompt and a set of FAILING cases (user \
messages where the agent's response scored a low reward, drew negative \
feedback, or was corrected by a human edit). Optionally you are given a RUBRIC \
describing what a good response looks like.

Your job is to propose improved system prompts using textual-gradient \
optimization (a.k.a. reflection on failures / OPRO):
  1. Diagnose the failure modes: read the failing cases and infer the specific,
     recurring ways the current prompt leads the agent astray. Ground each
     diagnosis in the evidence (reward, feedbackComment, editedAnswer).
  2. Propose 2-3 candidate revised system prompts that fix those failure modes
     while preserving everything the current prompt already does well. Make
     minimal, targeted edits - do not rewrite wholesale or bloat the prompt.
  3. Rank the candidates best-first and give each a short rationale plus an
     expectedImprovement estimate (a phrase like "moderate" / "large", or an
     approximate delta in the reward scale you were shown).

CRITICAL - optimize for genuine task success, never for gaming the score. \
The rewards you see may come from an automated judge or reward model. Do NOT \
craft prompts that flatter the judge, exploit its blind spots, pad responses \
with rubric keywords, or otherwise inflate the score without improving the \
real answer the end user receives. Optimize strictly for correct, helpful, \
honest task completion as described by the rubric. If a candidate would raise \
the measured reward but lower genuine quality, do not propose it.

Respond with ONLY a JSON object of this exact shape (no prose outside it):
{
  "candidates": [
    {
      "prompt": "<the full revised system prompt>",
      "rationale": "<why this fixes the observed failures>",
      "expectedImprovement": "<estimate>"
    }
  ]
}
Return the candidates already sorted best-first."""


def _format_cases(failing_cases: list[dict]) -> str:
    lines = []
    for i, c in enumerate(failing_cases, 1):
        lines.append(f"--- Failing case {i} ---")
        lines.append(f"User message: {c.get('userMessage', '')}")
        lines.append(f"Agent response: {c.get('response', '')}")
        if c.get("reward") is not None:
            lines.append(f"Reward: {c.get('reward')}")
        if c.get("feedbackComment"):
            lines.append(f"Human feedback: {c.get('feedbackComment')}")
        if c.get("editedAnswer"):
            lines.append(f"Human-corrected answer: {c.get('editedAnswer')}")
        lines.append("")
    return "\n".join(lines)


def optimize_prompt(settings: Settings, agent_id: str,
                    current_system_prompt: str, failing_cases: list[dict],
                    rubric: str | None) -> dict:
    """Run one round of LLM-driven prompt optimization and return the ranked
    candidates. Raises LlmUnavailableError/LlmCallError (mapped to 503/502 in
    main.py) when the LLM can't be reached."""
    user = [
        f"Agent id: {agent_id}",
        "",
        "CURRENT SYSTEM PROMPT:",
        current_system_prompt or "(empty)",
        "",
    ]
    if rubric:
        user += ["RUBRIC:", rubric, ""]
    user += [
        f"FAILING CASES ({len(failing_cases)}):",
        _format_cases(failing_cases),
    ]

    raw = complete(settings, _OPTIMIZER_SYSTEM, "\n".join(user))
    parsed = extract_json(raw)

    # Normalise: accept either the {candidates:[...]} envelope or a bare list.
    if isinstance(parsed, list):
        candidates = parsed
    elif isinstance(parsed, dict):
        candidates = parsed.get("candidates", [])
    else:
        candidates = []

    clean = []
    for c in candidates:
        if not isinstance(c, dict) or not c.get("prompt"):
            continue
        clean.append({
            "prompt": str(c.get("prompt", "")),
            "rationale": str(c.get("rationale", "")),
            "expectedImprovement": str(c.get("expectedImprovement", "")),
        })
    return {"candidates": clean}
