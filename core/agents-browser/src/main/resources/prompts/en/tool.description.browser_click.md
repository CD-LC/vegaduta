---
title: Tool description: browser_click
description: The description of the browser_click tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Clicks an element on the current page of this conversation's browser session (started with browser_navigate). The 'selector' argument accepts either a real CSS selector ('#submit', 'button.primary') or a plain description of the element's visible text ('Submit', 'the Log in button') - both work. Requires browser_navigate to have been called first.
