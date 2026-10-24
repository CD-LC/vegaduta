---
title: Tool description: browser_type
description: The description of the browser_type tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Types text into an input/textarea on the current page of this conversation's browser session (started with browser_navigate). The 'selector' argument accepts either a real CSS selector or a plain description of the field ('the email field', 'search box') - both work. Replaces any existing value in the field. Requires browser_navigate to have been called first.
