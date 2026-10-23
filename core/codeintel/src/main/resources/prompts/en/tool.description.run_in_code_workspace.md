---
title: Tool description: run_in_code_workspace
description: The description of the run_in_code_workspace tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Runs a shell command (e.g. a test suite or a build) inside an EXISTING code workspace created by create_code_workspace. This ALWAYS requires a tenant-admin's sign-off on this platform's Approvals screen before anything runs - this call only files the request and returns immediately; it does not run anything itself and does not return exit code/stdout/stderr. After a human approves, the command runs automatically.
