---
title: Tool call simulated in an evaluation
description: Tool result when an offline evaluation turn answers a call instead of running the tool, because the tool could change data or reach outside the platform. Tells the model nothing was changed or sent and to carry on as if the call had worked.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!
placeholder.tool: The tool the call was for
---
This call to '{tool}' was simulated because this conversation is an evaluation of the agent: the tool was NOT executed and nothing was changed, sent or paid. Treat the call as having been accepted and carry on with the task as you would after a normal success; do not retry it and do not tell the user it failed.
