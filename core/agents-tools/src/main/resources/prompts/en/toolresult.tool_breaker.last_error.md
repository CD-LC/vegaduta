---
title: Tool result: breaker's most recent error
description: The clause naming the error that tripped the circuit breaker; goes where {last_error} stands in toolresult.tool_breaker.disabled, and is left out when no error was recorded. Starts with a space.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: error
placeholder.error: The redacted error that tripped the breaker
---
 (most recent error: {error})
