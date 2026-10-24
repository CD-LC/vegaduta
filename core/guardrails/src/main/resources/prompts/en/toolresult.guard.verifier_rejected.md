---
title: Tool call rejected by the verifier
description: Tool result when the independent tool-call verifier (RIGOROUS agents) judges a call not to match the request.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, reason!
placeholder.tool: The tool the call was for
placeholder.reason: The verifier's one-sentence reason
---
This tool call ('{tool}') was not executed - an independent check found it didn't match your request: {reason}. Try again with a call that matches what was actually asked, or ask the user to clarify.
