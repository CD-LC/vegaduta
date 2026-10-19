"""app/judge.py - constitutional critique normalisation and score clamping."""

from __future__ import annotations

import json
from collections.abc import Callable

import pytest

from app import judge
from app.settings import Settings

MakeSettings = Callable[..., Settings]


def _run(
    monkeypatch: pytest.MonkeyPatch,
    settings: Settings,
    raw: str,
    principles: list[str] | None = None,
) -> tuple[dict, list[tuple[str, str]]]:
    seen: list[tuple[str, str]] = []

    def fake_complete(s: Settings, system: str, user: str, max_tokens: int | None = None) -> str:
        seen.append((system, user))
        return raw

    monkeypatch.setattr(judge, "complete", fake_complete)
    return judge.critique(settings, "the answer", principles or ["Be honest."]), seen


@pytest.mark.parametrize(
    ("score", "expected"),
    [
        (0.42, 0.42),
        (0, 0.0),
        (1, 1.0),
        (1.7, 1.0),
        (-0.3, 0.0),
        ("0.5", 0.5),
        ("high", 0.0),
        (None, 0.0),
        ([0.9], 0.0),
        ({"v": 1}, 0.0),
    ],
)
def test_score_is_clamped_to_unit_interval(
    monkeypatch: pytest.MonkeyPatch,
    make_settings: MakeSettings,
    score: object,
    expected: float,
) -> None:
    out, _ = _run(monkeypatch, make_settings(), json.dumps({"score": score, "violations": []}))
    assert out["score"] == pytest.approx(expected)


@pytest.mark.parametrize(
    ("literal", "expected"),
    [("NaN", 0.0), ("Infinity", 1.0), ("-Infinity", 0.0)],
)
def test_non_finite_scores_never_fail_open(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings, literal: str, expected: float
) -> None:
    # json.loads accepts these bare literals; NaN in particular used to clamp
    # to a perfect 1.0 (min(1.0, nan) == 1.0).
    out, _ = _run(monkeypatch, make_settings(), f'{{"score": {literal}, "violations": []}}')
    assert out["score"] == expected


def test_missing_score_or_non_object_response_scores_zero(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    assert _run(monkeypatch, make_settings(), '{"violations": []}')[0]["score"] == 0.0
    out, _ = _run(monkeypatch, make_settings(), "[0.9, 1.0]")
    assert out == {"score": 0.0, "violations": []}


def test_violations_are_normalised(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    raw = json.dumps(
        {
            "score": 0.3,
            "violations": [
                {"principle": "Be honest.", "explanation": "invented a policy"},
                {"principle": 7},
                "Cite sources.",
                42,
                None,
            ],
        }
    )
    out, _ = _run(monkeypatch, make_settings(), raw)
    assert out["violations"] == [
        {"principle": "Be honest.", "explanation": "invented a policy"},
        {"principle": "7", "explanation": ""},
        {"principle": "Cite sources.", "explanation": ""},
    ]


def test_violations_not_a_list_is_ignored(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    out, _ = _run(monkeypatch, make_settings(), '{"score": 1, "violations": "none"}')
    assert out == {"score": 1.0, "violations": []}


def test_prompt_numbers_principles_and_includes_response(
    monkeypatch: pytest.MonkeyPatch, make_settings: MakeSettings
) -> None:
    _, seen = _run(monkeypatch, make_settings(), '{"score": 1}', ["First.", "Second."])
    ((system, user),) = seen
    assert "1. First." in user
    assert "2. Second." in user
    assert user.rstrip().endswith("the answer")
    # The judge is told to score rubric-gaming LOW (reward-hacking safety).
    assert "score LOW" in system
