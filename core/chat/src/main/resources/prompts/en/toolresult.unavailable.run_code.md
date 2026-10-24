---
title: Unavailable tool: code execution
description: Tool result when the model calls run_code but the workspace has no E2B key.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
---
Code execution is not switched on for this workspace, so this tool cannot run. It needs an E2B API key on the Providers page. Tell the user that and do not call this tool again in this conversation.
