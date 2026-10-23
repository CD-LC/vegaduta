---
title: Hook: tool output withheld
description: Tool result the model reads when a workspace hook withheld what the tool returned.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: tool!, reason!
placeholder.tool: Name of the tool
placeholder.reason: Why, without a trailing period
---
Tool output from '{tool}' was withheld by a workspace policy and not returned to the model: {reason}. Try a different approach, or tell the user this result is not available.
