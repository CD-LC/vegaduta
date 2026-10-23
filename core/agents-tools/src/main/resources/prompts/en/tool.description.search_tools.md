---
title: Tool description: search_tools
description: The description of the search_tools tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Finds more tools by keyword when none of your listed tools fit. Returns matching tool names with their input schemas and makes them callable via call_tool. Use select:name1,name2 to load tools by exact name.
