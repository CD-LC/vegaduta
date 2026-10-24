---
title: Tool call refused by argument policy
description: Tool result when the arguments of a call break the agent's per-tool argument policy.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, reason!
placeholder.tool: The tool the call was for
placeholder.reason: Which constraint the arguments broke
---
This tool call ('{tool}') was not executed: its arguments broke this agent's argument policy for the tool - {reason}. Fix the arguments and try again, or proceed without this tool.
