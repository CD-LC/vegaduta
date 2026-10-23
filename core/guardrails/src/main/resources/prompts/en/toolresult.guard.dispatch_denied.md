---
title: Tool call refused by tool policy
description: Tool result when the workspace tool policy (tool classification, hooks) refuses a call before it runs.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, reason!
placeholder.tool: The tool the call was for
placeholder.reason: Why the policy refused it (the policy supplies it, or the default reason key)
---
This tool call ('{tool}') was not executed: {reason} Do not retry this tool - proceed without it or tell the user it is unavailable.
