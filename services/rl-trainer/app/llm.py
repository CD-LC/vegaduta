"""Thin Anthropic client wrapper for the LLM-driven optimizer and judge.

The anthropic SDK is imported lazily so the container boots (and /health
answers) even when the package or ANTHROPIC_API_KEY is absent - only the LLM
endpoints require it. The API key is read from the environment by the SDK
itself; it is never copied into our settings or echoed in an error body.
"""

from __future__ import annotations

import json
import re

from .settings import Settings


class LlmUnavailableError(Exception):
    """Raised (=> HTTP 503) when the LLM cannot be called: SDK not installed,
    or ANTHROPIC_API_KEY not configured. Kept distinct from a genuine LLM
    error so the caller can tell 'not wired up' from 'call failed'."""


class LlmCallError(Exception):
    """Raised (=> HTTP 502) when the LLM call itself failed (network, rate
    limit, API error). The message is safe to surface; it never contains the
    API key."""


_CLIENT = None  # lazily constructed anthropic.Anthropic()


def _client(settings: Settings):
    global _CLIENT
    if not settings.llm_api_key_present:
        raise LlmUnavailableError(
            "ANTHROPIC_API_KEY is not configured; the LLM-driven endpoints "
            "(/optimize/prompt, /judge/constitutional) are unavailable. Set "
            "the key to enable them.")
    if _CLIENT is None:
        try:
            import anthropic
        except ImportError as e:
            raise LlmUnavailableError(
                "the 'anthropic' package is not installed in this image") from e
        _CLIENT = anthropic.Anthropic()
    return _CLIENT


def complete(settings: Settings, system: str, user: str,
             max_tokens: int | None = None) -> str:
    """Single-turn completion. Returns the concatenated text of the response.

    Uses adaptive thinking (the recommended setting on claude-sonnet-5) so the
    model reasons about the failing cases before proposing candidates, while
    letting it decide how much to think per request.
    """
    client = _client(settings)
    try:
        resp = client.messages.create(
            model=settings.llm_model,
            max_tokens=max_tokens or settings.llm_max_tokens,
            thinking={"type": "adaptive"},
            system=system,
            messages=[{"role": "user", "content": user}],
        )
    except LlmUnavailableError:
        raise
    except Exception as e:  # anthropic.APIError and friends
        raise LlmCallError(f"LLM call failed: {type(e).__name__}: {e}") from e

    parts = [b.text for b in resp.content if getattr(b, "type", None) == "text"]
    return "".join(parts).strip()


def extract_json(text: str):
    """Best-effort parse of a JSON object/array from an LLM response.

    Tries the whole string first, then the first fenced ```json block, then
    the outermost {...} / [...] span. Raises LlmCallError if nothing parses -
    the caller turns that into a 502 rather than crashing.
    """
    text = text.strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    fence = re.search(r"```(?:json)?\s*(.+?)```", text, re.DOTALL)
    if fence:
        try:
            return json.loads(fence.group(1).strip())
        except json.JSONDecodeError:
            pass

    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start = text.find(open_ch)
        end = text.rfind(close_ch)
        if start != -1 and end != -1 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except json.JSONDecodeError:
                continue

    raise LlmCallError("could not parse JSON from the LLM response")
