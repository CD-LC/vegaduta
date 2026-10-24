---
title: Tool description: code_callees
description: The description of the code_callees tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists everything a given function/method/symbol itself calls, from the deterministic code graph (not a guess) - the reverse of code_callers. Use to understand what a symbol depends on before changing or removing it. Requires the repo_id the symbol lives in.
