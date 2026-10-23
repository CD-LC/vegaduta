---
title: Tool description: call_tool
description: The description of the call_tool tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Runs a tool found with search_tools. Pass its exact name and its arguments as a JSON object string that follows the inputSchema search_tools returned.
