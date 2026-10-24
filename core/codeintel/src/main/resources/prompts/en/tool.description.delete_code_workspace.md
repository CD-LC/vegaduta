---
title: Tool description: delete_code_workspace
description: The description of the delete_code_workspace tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Tears down a scratch code workspace once you're done with it (removes the git worktree or scratch copy). Does not touch the primary checkout or any other workspace. Use this to clean up after finishing a change.
