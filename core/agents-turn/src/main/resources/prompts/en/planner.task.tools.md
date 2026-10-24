---
title: Task planner, agent tool list
description: Appended to planner.task when the agent has tools, naming them so the plan holds only steps those tools or plain reasoning can do. The text starts and ends with a line break; they separate it from the system prompt.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 2000
placeholders: tools!
placeholder.tools: The agent's tool names, comma-separated
---

The agent's tools are: {tools}. Plan only steps these tools or the agent's own reasoning can do. Do not add steps like "identify the current date" (the date is already in the agent's prompt). If the request needs a capability none of these tools provide, make the step say what is missing.

