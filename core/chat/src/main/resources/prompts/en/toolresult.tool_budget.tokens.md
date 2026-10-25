---
title: Prompt-token budget reached
description: Instruction inside the tool_budget_exhausted result for calls after the run's cumulative prompt-token cap.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
---
Token budget for this reply is exhausted, so this call was NOT executed. Do not call any more tools. Answer the user now using only what you already have, say briefly if anything is incomplete, and if the work needs more calls than one reply allows, tell the user it can continue in the background as a job.
