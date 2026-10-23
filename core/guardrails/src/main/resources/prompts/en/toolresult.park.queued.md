---
title: Tool call queued behind an approval
description: Tool result for a call made after the turn parked: recorded as its own pending approval.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, requestId!, parkedTool!
placeholder.tool: The tool of the queued call
placeholder.requestId: The approval request id of the queued call
placeholder.parkedTool: The call the turn is parked behind
---
This tool call ('{tool}') was NOT executed: an earlier action in this turn ('{parkedTool}') is waiting for human approval, and nothing else runs until it is decided. This call has been queued as its own approval request ({requestId}) and will run once it is approved, so do not repeat it. Your goal has not changed: your task is resumed automatically after the approvals are decided. Briefly tell the user what is waiting, then end this turn without calling more tools.
