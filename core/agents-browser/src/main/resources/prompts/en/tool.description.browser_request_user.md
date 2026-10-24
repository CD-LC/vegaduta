---
title: Tool description: browser_request_user
description: The description of the browser_request_user tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Ask the user to take over the browser for a step you cannot or must not do yourself: a captcha, a 2FA or one-time code prompt, or a login you have no saved credentials for. The browser pauses for them and you cannot use it until they hand it back. Give a short 'reason' telling them what to do. Never ask the user to type a password or code to you in chat. After they say they are done, call again with action "status".
