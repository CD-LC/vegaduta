"""Shared schema fragments for MCP tool definitions (mcp_bridge and
mcp_analysis_tools both build input schemas on the same repo property)."""


def _repo_prop() -> dict:
    return {
        "type": "string",
        "description": "Repo id registered in code-intel. Omit to use "
        "the connection's default repo.",
    }
