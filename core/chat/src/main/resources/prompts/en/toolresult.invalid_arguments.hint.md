---
title: Tool result: arguments rejected before the tool ran
description: The hint in the result a tool call gets when its arguments do not match the tool's input schema; the tool was not run. The schema follows in the same result.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: tool!, problems!, unknown_keys
placeholder.tool: The tool's name
placeholder.problems: What is wrong, joined with '; ' (the problem.* prompts)
placeholder.unknown_keys: The unknown-keys sentence (toolresult.invalid_arguments.unknown_keys), or empty
---
The call to {tool} was NOT executed: {problems}. {unknown_keys}Call {tool} again with arguments that match its schema.
