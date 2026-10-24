---
title: Tool call refused, queue full
description: Tool result for a call made after the park when enough calls are already queued; nothing is recorded.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, parkedTool!
placeholder.tool: The tool of the refused call
placeholder.parkedTool: The call the turn is parked behind
---
This tool call ('{tool}') was NOT executed and was not queued: an earlier action in this turn ('{parkedTool}') is waiting for human approval and enough follow-up calls are already queued behind it. Do not call more tools. Your goal has not changed: briefly tell the user what is waiting for approval and end this turn; your task is resumed automatically once the approvals are decided.
