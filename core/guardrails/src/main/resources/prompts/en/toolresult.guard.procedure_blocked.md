---
title: Tool call blocked by a procedure
description: Tool result when a workspace procedure rule (FORBID_TOOL, unmet prerequisite) blocks a call.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, procedure!, instruction!
placeholder.tool: The tool the call was for
placeholder.procedure: Name of the procedure
placeholder.instruction: The rule instruction the author wrote
---
This tool call ('{tool}') was blocked by the workspace procedure "{procedure}": {instruction} Do not retry this tool - proceed without it or ask the user how to continue.
