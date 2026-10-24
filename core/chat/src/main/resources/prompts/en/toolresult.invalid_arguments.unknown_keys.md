---
title: Tool result: keys the tool does not know
description: The sentence naming the argument keys that are not parameters of the tool; goes where {unknown_keys} stands in toolresult.invalid_arguments.hint and is left out when there are none. Ends with a space.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: tool, keys
placeholder.tool: The tool's name
placeholder.keys: The unknown keys, comma separated
---
These keys are not parameters of {tool} and were ignored: {keys}. 
