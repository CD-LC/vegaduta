---
title: Unknown tool name
description: Tool result when the model calls a tool name that was never offered (a hallucinated name).
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
placeholders: tool!
placeholder.tool: The tool name the model asked for
---
There is no tool called "{tool}". It was not among the tools provided with this request, so it cannot be called - you invented the name. Do not call it again. Answer the user directly using only the tools you were actually given, and if what they asked for genuinely needs a capability you do not have, say so plainly and briefly.
