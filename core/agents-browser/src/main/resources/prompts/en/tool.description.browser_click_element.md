---
title: Tool description: browser_click_element
description: The description of the browser_click_element tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Clicks element [index] from the latest browser_snapshot. If the page changed you get an error: take a new snapshot. Buy/pay/confirm/submit/delete/send-style elements are NOT clicked by this tool - use browser_confirm_click for those.
