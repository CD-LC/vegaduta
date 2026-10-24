---
title: Tool output withheld by a safety check
description: Tool result handed to the model when a guardrail (pattern, semantic judge, tenant rule) withholds a tool's output.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
placeholders: tool!, reason!
placeholder.tool: The tool whose output was withheld
placeholder.reason: Why the guardrail withheld it
---
Tool output from '{tool}' was withheld by a content-safety check and not returned to the model: {reason}. This may be a false positive (e.g. the tool's own error text misread as unsafe) - try a different approach, or tell the user this capability hit a safety check and to try again; an admin has been notified.
