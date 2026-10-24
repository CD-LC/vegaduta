---
title: Constrained tool router: required tool call
description: System message added in REQUIRED router mode (vLLM): every reply must be a tool call, ending with the final-answer tool.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
placeholders: tool!
placeholder.tool: Name of the final-answer tool
---
Every reply must be a tool call. Call {tool} with your reply to the user when no other tool is needed or you have the results you need.
