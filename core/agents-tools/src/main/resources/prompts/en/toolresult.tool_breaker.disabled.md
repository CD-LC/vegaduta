---
title: Tool result: tool disabled by its circuit breaker
description: The result of a call to a tool whose circuit breaker is open after repeated failures. The tool did not run.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: tool, last_error, wait
placeholder.tool: The tool's name
placeholder.last_error: The most recent error, as its own wording (toolresult.tool_breaker.last_error), or empty when none was recorded
placeholder.wait: How long until the tool is tried again (toolresult.tool_breaker.wait.one / .many)
---
This tool ('{tool}') has been temporarily disabled after repeated failures{last_error}. It becomes available again automatically in about {wait}. Try a different approach, or let the user know this capability is briefly unavailable.
