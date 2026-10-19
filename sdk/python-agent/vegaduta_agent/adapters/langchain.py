"""LangChain adapter: turn a ``GovernedTool`` into a LangChain ``BaseTool``.

Requires ``langchain-core`` (``pip install vegaduta-agent[langchain]``). The
governance pipeline stays in ``vegaduta_agent`` — this file only translates
the call surface, so approvals, budgets, the journal and the audit record are
identical whether the agent runs on LangChain, a hand-rolled loop or another
framework.
"""

from __future__ import annotations

from typing import Any

from ..context import RunContext
from ..governance import GovernedTool


def to_langchain_tool(tool: GovernedTool, context: RunContext | None = None):
    """Wrap a governed tool as a LangChain ``StructuredTool``. The fixed
    ``context`` is optional; without one every call gets a fresh anonymous run
    (independent budgets, no cross-call journal)."""
    try:
        from langchain_core.tools import StructuredTool  # noqa: PLC0415
    except ImportError as exc:
        raise ImportError(
            "the langchain adapter needs the langchain extra: pip install vegaduta-agent[langchain]"
        ) from exc

    def _invoke(**kwargs: Any) -> str:
        return tool(kwargs, context=context)

    description = tool.describe()
    return StructuredTool.from_function(
        func=_invoke,
        name=tool.tool_name,
        description=description["description"] or tool.tool_name,
    )


def to_langchain_tools(
    tools: dict[str, GovernedTool] | list[GovernedTool], context: RunContext | None = None
) -> list:
    """Batch adapter for ``Governance.scan`` results."""
    items = tools.values() if isinstance(tools, dict) else tools
    return [to_langchain_tool(t, context) for t in items]
