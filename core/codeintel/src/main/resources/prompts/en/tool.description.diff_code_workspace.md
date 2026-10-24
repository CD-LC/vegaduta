---
title: Tool description: diff_code_workspace
description: The description of the diff_code_workspace tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Returns the unified diff of everything changed so far in a code workspace (empty for non-git repos). Read-only. Use this to see what an apply_code_changes or run_in_code_workspace call actually did, once approved and executed.
