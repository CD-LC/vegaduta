---
title: Tool description: code_callers
description: The description of the code_callers tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists every place that calls a given function/method/symbol, from the deterministic code graph (not a guess). Use before modifying a symbol to understand its usage. Requires the repo_id the symbol lives in.
