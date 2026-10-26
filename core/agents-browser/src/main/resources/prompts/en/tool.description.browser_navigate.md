---
title: Tool description: browser_navigate
description: The description of the browser_navigate tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Drives a real headless browser to a URL - use this ONLY as a fallback when no dedicated tool or integration can do the task (no API for this site, a login-gated portal, a JavaScript-heavy flow that needs real clicking/typing). Starts this conversation's browser session if one doesn't exist yet, or re-points the existing one. Follow with browser_read_page_text or browser_screenshot to see what loaded, and browser_click/browser_type to interact with it. Only public http/https URLs work - internal/private network addresses are refused. IMPORTANT framing for your reply: this browser is YOUR sandboxed container browser - invisible and inaudible to the person (never say you opened their browser, their Chrome, or that something is playing audibly on their machine; media plays only inside the sandbox). To act on the person's own screen the local_desktop_* tools are required - if they are unavailable, say so instead of substituting this browser silently. When the outcome is visual (a page, a playing video), finish with browser_screenshot so the person can see what actually happened.
