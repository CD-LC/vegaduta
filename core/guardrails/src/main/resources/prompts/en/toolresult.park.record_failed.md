---
title: Approval request could not be recorded
description: Tool result when the approval request itself could not be stored: nothing runs and nothing waits.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!
placeholder.tool: The tool that needed approval
---
This action ('{tool}') requires human approval, but the approval request could not be recorded, so it was NOT executed and nothing is waiting for approval. Do not retry it. Tell the user plainly that the action could not be submitted for approval and that they can ask again, then carry on with any part of the task that does not need this action.
