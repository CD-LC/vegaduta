"""Offline stand-in for the anthropic SDK client used by app/llm.py.

Records every messages.create() call and replays scripted responses (or
raises a scripted exception), so the LLM path runs end to end without the
network or a real API key.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Block:
    type: str
    text: str = ""


@dataclass
class Response:
    content: list[Block]


@dataclass
class _Messages:
    replies: list[str | BaseException | Response]
    calls: list[dict[str, Any]] = field(default_factory=list)

    def create(self, **kwargs: Any) -> Response:
        self.calls.append(kwargs)
        reply = self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]
        if isinstance(reply, BaseException):
            raise reply
        if isinstance(reply, Response):
            return reply
        return Response(content=[Block(type="thinking"), Block(type="text", text=reply)])


class FakeAnthropic:
    def __init__(self, *replies: str | BaseException | Response) -> None:
        self.messages = _Messages(list(replies) or [""])

    @property
    def calls(self) -> list[dict[str, Any]]:
        return self.messages.calls
