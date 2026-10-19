"""A single model-requested tool invocation.

Mirrors ``io.vegaduta.agent.api.tool.ToolCall``. ``arguments_json`` is the raw
argument payload the model produced — kept as text so the audit record and the
effect journal hash the exact bytes that were requested, not a re-serialised
approximation.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass


@dataclass(frozen=True)
class ToolCall:
    tool_name: str
    arguments_json: str
    call_id: str = ""

    def __post_init__(self) -> None:
        if not self.tool_name:
            raise ValueError("tool_name is required")
        if self.arguments_json is None:
            raise ValueError("arguments_json is required")
        if not self.call_id:
            object.__setattr__(self, "call_id", str(uuid.uuid4()))

    @classmethod
    def of(cls, tool_name: str, arguments_json: str = "{}") -> ToolCall:
        return cls(tool_name=tool_name, arguments_json=arguments_json)
