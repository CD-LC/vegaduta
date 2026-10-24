---
title: Tool description: browser_screenshot
description: The description of the browser_screenshot tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Captures a screenshot (base64-encoded JPEG, image/jpeg) of the current viewport in this conversation's browser session (started with browser_navigate) - use this when you need to SEE the page's layout rather than just its text. For most 'what does this page say' questions prefer browser_read_page_text, which is cheaper and never fails on size. Requires browser_navigate to have been called first.
