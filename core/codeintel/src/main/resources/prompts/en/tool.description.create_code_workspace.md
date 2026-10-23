---
title: Tool description: create_code_workspace
description: The description of the create_code_workspace tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Creates a new isolated scratch workspace for a repo (a git worktree or a copy - never the primary checkout). Returns a workspace_id to pass to apply_code_changes, run_in_code_workspace, diff_code_workspace, and delete_code_workspace. Call this first before trying to change or run anything against a repo's code.
