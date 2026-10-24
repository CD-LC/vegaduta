---
title: Tool-round cap reached
description: Instruction inside the tool_budget_exhausted result for calls past the tool loop's round cap.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: rounds
placeholder.rounds: How many tool rounds the reply has used
---
Tool budget for this reply is exhausted after {rounds} tool round(s), so this call was NOT executed. Do not call any more tools. Answer the user now using only what you already have, say briefly if anything is incomplete, and if the work needs more calls than one reply allows, tell the user it can continue in the background as a job.
