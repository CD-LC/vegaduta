---
title: Tool description: browser_snapshot
description: The description of the browser_snapshot tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists the current browser page's visible interactive elements as a numbered list '[index] role 'name' (value, state)' plus URL, title and a text digest. Call it before browser_click_element, browser_type_into or browser_select_option, and again after the page changes. Requires browser_navigate first.
