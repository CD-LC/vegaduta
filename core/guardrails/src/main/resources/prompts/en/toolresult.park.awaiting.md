---
title: Tool call held for approval
description: Tool result for the call that parks the turn on a human approval: tells the model to stop, not retry, not claim it was done, and that the task resumes automatically.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, requestId!
placeholder.tool: The tool held for approval
placeholder.requestId: The approval request id
---
This action ('{tool}') requires human approval before it can run and was NOT executed. An approval request ({requestId}) has been sent to a tenant-admin. Once it is approved the action runs automatically with these exact arguments, and your task is resumed automatically with its result, so do not retry the action yourself and do not claim it was done. Your goal has not changed and you are not finished: you are waiting on this approval. Briefly tell the user what is waiting for approval, then end this turn without calling more tools; nothing else runs in this turn until the human has decided.
