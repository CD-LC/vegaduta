"""OpenAI-compatible tool calling — dependency-free.

The ``tools=[...]`` schema and the ``tool_calls`` dispatch shape used by the
OpenAI API (and by LiteLLM, Azure OpenAI, Ollama's OpenAI mode, vLLM, and most
frameworks that emit function calls) — without importing any of them. Your
loop stays yours::

    tools = {t.tool_name: t for t in governance.scan(my_module).values()}
    resp = client.chat.completions.create(model=..., tools=openai_tools(tools.values()))
    for call in resp.choices[0].message.tool_calls or []:
        result = dispatch_tool_call(call.model_dump(), tools, context)
        messages.append({"role": "tool", "tool_call_id": call.id,
                         "content": result})
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping

from ..context import RunContext

_TYPES = {
    "str": "string",
    "int": "integer",
    "float": "number",
    "bool": "boolean",
    "dict": "object",
    "list": "array",
    "NoneType": "string",
}


def _schema_for(describe: dict) -> dict:
    """Maps ``GovernedTool.describe()``'s parameter list onto a JSON Schema
    object."""
    properties = {}
    required = []
    for p in describe.get("parameters", []):
        properties[p["name"]] = {
            "type": _TYPES.get(str(p.get("type")), "string"),
        }
        if p.get("default") is not None:
            properties[p["name"]]["default"] = p["default"]
        if p.get("required"):
            required.append(p["name"])
    return {
        "type": "object",
        "properties": properties,
        "required": required,
        "additionalProperties": False,
    }


def openai_tools(tools: Iterable) -> list[dict]:
    """The ``tools=[...]`` argument for a chat-completions call, built from
    ``GovernedTool`` objects (or anything with a ``describe()`` of the same
    shape)."""
    out = []
    for tool in tools:
        describe = tool.describe() if hasattr(tool, "describe") else tool
        out.append(
            {
                "type": "function",
                "function": {
                    "name": describe["name"],
                    "description": describe.get("description") or "",
                    "parameters": _schema_for(describe),
                },
            }
        )
    return out


def dispatch_tool_call(
    tool_call: Mapping,
    tools: Mapping[str, object],
    context: RunContext | None = None,
) -> str:
    """Runs one ``tool_calls`` entry through its governed tool and returns the
    string result — including the ``approvalRequired`` / ``denied`` JSON the
    pipeline produces, which is exactly what the model should see back.

    Accepts either an OpenAI-shaped dict (``{"id", "type": "function",
    "function": {"name", "arguments"}}``) or a plain ``{"name",
    "arguments"}``. Unknown tools return an ``unknown_tool`` error string
    instead of raising, so the model loop keeps control."""
    function = tool_call.get("function") or tool_call
    name = function.get("name", "")
    arguments = function.get("arguments") or "{}"
    if not isinstance(arguments, str):
        arguments = json.dumps(arguments)
    tool = tools.get(name)
    if tool is None:
        return json.dumps({"error": "unknown_tool", "tool": name})
    return tool(arguments, context=context)
