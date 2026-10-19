"""app/llm.py - lazy client, fail-closed without a key, JSON extraction."""

from __future__ import annotations

from collections.abc import Callable

import pytest

from app import llm
from app.llm import LlmCallError, LlmUnavailableError, complete, extract_json
from app.settings import Settings

from .fakes import Block, FakeAnthropic, Response

MakeSettings = Callable[..., Settings]


def test_no_api_key_is_unavailable_and_never_builds_a_client(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    import anthropic

    def boom() -> None:
        raise AssertionError("client must not be constructed without a key")

    monkeypatch.setattr(anthropic, "Anthropic", boom)
    with pytest.raises(LlmUnavailableError, match="ANTHROPIC_API_KEY"):
        complete(make_settings(llm_api_key_present=False), "sys", "user")


def test_client_is_built_lazily_once(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    import anthropic

    built: list[FakeAnthropic] = []

    def ctor() -> FakeAnthropic:
        built.append(FakeAnthropic("one", "two"))
        return built[-1]

    monkeypatch.setattr(anthropic, "Anthropic", ctor)
    s = make_settings(llm_api_key_present=True)
    assert complete(s, "sys", "u1") == "one"
    assert complete(s, "sys", "u2") == "two"
    assert len(built) == 1


def test_complete_request_shape_and_text_only_concatenation(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeAnthropic(
        Response(
            content=[
                Block(type="thinking", text="hidden reasoning"),
                Block(type="text", text="  hello "),
                Block(type="tool_use", text="ignored"),
                Block(type="text", text="world  "),
            ]
        )
    )
    monkeypatch.setattr(llm, "_CLIENT", fake)
    s = make_settings(llm_api_key_present=True, llm_model="claude-x", llm_max_tokens=321)

    assert complete(s, "SYSTEM", "USER") == "hello world"
    (call,) = fake.calls
    assert call["model"] == "claude-x"
    assert call["max_tokens"] == 321
    assert call["system"] == "SYSTEM"
    assert call["messages"] == [{"role": "user", "content": "USER"}]
    assert call["thinking"] == {"type": "adaptive"}


def test_complete_max_tokens_override(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    fake = FakeAnthropic("ok")
    monkeypatch.setattr(llm, "_CLIENT", fake)
    complete(make_settings(llm_api_key_present=True), "s", "u", max_tokens=7)
    assert fake.calls[0]["max_tokens"] == 7


def test_sdk_error_becomes_llm_call_error(
    make_settings: MakeSettings, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(llm, "_CLIENT", FakeAnthropic(ConnectionError("upstream down")))
    with pytest.raises(LlmCallError, match="ConnectionError: upstream down"):
        complete(make_settings(llm_api_key_present=True), "s", "u")


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ('{"a": 1}', {"a": 1}),
        ("  [1, 2]  ", [1, 2]),
        ('Sure!\n```json\n{"a": 2}\n```\nDone.', {"a": 2}),
        ('```\n{"a": 3}\n```', {"a": 3}),
        ('Here you go: {"a": {"b": 4}} hope that helps', {"a": {"b": 4}}),
        ("prefix [1, 2, 3] suffix", [1, 2, 3]),
    ],
)
def test_extract_json_variants(text: str, expected: object) -> None:
    assert extract_json(text) == expected


@pytest.mark.parametrize("text", ["", "no json here", "{not: valid}", "] backwards ["])
def test_extract_json_failure_is_llm_call_error(text: str) -> None:
    with pytest.raises(LlmCallError):
        extract_json(text)
