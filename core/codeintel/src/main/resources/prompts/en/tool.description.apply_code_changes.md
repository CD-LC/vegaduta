---
title: Tool description: apply_code_changes
description: The description of the apply_code_changes tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Writes full file contents (or deletes files) into an EXISTING code workspace created by create_code_workspace. This ALWAYS requires a tenant-admin's sign-off on this platform's Approvals screen before anything is written - this call only files the request and returns immediately; it does not write anything itself and does not return the outcome. After a human approves, the write happens automatically; call diff_code_workspace afterward to see the result.
