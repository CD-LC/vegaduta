---
title: Tool-call ceiling reached
description: Instruction inside the tool_budget_exhausted result for every call made after the turn's tool-call ceiling.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: limit
placeholder.limit: The tool-call ceiling that applied
---
Tool budget for this reply is exhausted: {limit} tool call(s) have been made, so this call was NOT executed. Do not call any more tools. Answer the user now using only what you already have, say briefly what is incomplete and what you would do next, and if the work needs more calls than one reply allows, tell the user it can continue in the background as a job (they can ask for that, or you can hand it off with continue_in_background next turn if the tool is offered).
