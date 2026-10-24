---
title: Hook: tool call denied
description: Tool result the model reads when a workspace hook stopped the call.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: tool!, reason!
placeholder.tool: Name of the tool
placeholder.reason: Why, without a trailing period
---
This tool call ('{tool}') was not executed: {reason}. Do not retry this tool - proceed without it or tell the user it is unavailable.
