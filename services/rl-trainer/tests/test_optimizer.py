"""app/optimizer.py - prompt-optimizer happy path with a fake LLM."""

from __future__ import annotations

import json
from collections.abc import Callable

import pytest

from app import optimizer
from app.llm import LlmCallError
from app.settings import Settings

MakeSettings = Callable[..., Settings]

CASES = [
    {
        "userMessage": "How do I get a refund?",
        "response": "I can't help with that.",
        "reward": 0.1,
        "feedbackComment": "Unhelpful; refunds are supported.",
        "editedAnswer": "Settings > Billing > Refund.",
    },
    {
        "userMessage": "Hi",
        "response": "Hello",
        "reward": None,
        "feedbackComment": None,
        "editedAnswer": None,
    },
]


def _patch(monkeypatch: pytest.MonkeyPatch, raw: str) -> list[tuple[str, str]]:
    seen: list[tuple[str, str]] = []

    def fake_complete(s: Settings, system: str, user: str, max_tokens: int | None = None) -> str:
        seen.append((system, user))
        return raw

    monkeypatch.setattr(optimizer, "complete", fake_complete)
    return seen


def test_happy_path_returns_ranked_clean_candidates(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    raw = (
        "Here are my candidates:\n```json\n"
        + json.dumps(
            {
                "candidates": [
                    {"prompt": "P1", "rationale": "fixes refunds", "expectedImprovement": "large"},
                    {"prompt": "", "rationale": "empty prompt is dropped"},
                    "not an object",
                    {"prompt": "P2", "rationale": 5},
                    {"prompt": "P3", "rationale": "r3", "expectedImprovement": "small", "x": 1},
                ]
            }
        )
        + "\n```"
    )
    seen = _patch(monkeypatch, raw)

    out = optimizer.optimize_prompt(
        make_settings(), "agent-123", "You are a support agent.", CASES, "Cite the UI path."
    )

    assert out == {
        "candidates": [
            {"prompt": "P1", "rationale": "fixes refunds", "expectedImprovement": "large"},
            {"prompt": "P2", "rationale": "5", "expectedImprovement": ""},
            {"prompt": "P3", "rationale": "r3", "expectedImprovement": "small"},
        ]
    }
    ((system, user),) = seen
    assert "Agent id: agent-123" in user
    assert "You are a support agent." in user
    assert "RUBRIC:\nCite the UI path." in user
    assert "FAILING CASES (2):" in user
    assert "Reward: 0.1" in user
    assert "Human feedback: Unhelpful; refunds are supported." in user
    assert "Human-corrected answer: Settings > Billing > Refund." in user
    # optional fields that are None are omitted, not rendered as "None"
    assert "None" not in user
    # Reward-hacking guard is part of the optimizer's instructions.
    assert "never for gaming the score" in system


def test_bare_list_and_no_rubric_and_empty_prompt(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    seen = _patch(monkeypatch, json.dumps([{"prompt": "only"}]))
    out = optimizer.optimize_prompt(make_settings(), "a", "", CASES[:1], None)
    assert out == {"candidates": [{"prompt": "only", "rationale": "", "expectedImprovement": ""}]}
    user = seen[0][1]
    assert "RUBRIC" not in user
    assert "(empty)" in user


@pytest.mark.parametrize("raw", ['{"candidates": "nope"}', '"just a string"', "42"])
def test_unexpected_shapes_yield_no_candidates(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings, raw: str
) -> None:
    _patch(monkeypatch, raw)
    assert optimizer.optimize_prompt(make_settings(), "a", "p", CASES, None) == {"candidates": []}


def test_unparseable_llm_output_raises(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    _patch(monkeypatch, "I would rather not answer in JSON.")
    with pytest.raises(LlmCallError):
        optimizer.optimize_prompt(make_settings(), "a", "p", CASES, None)
