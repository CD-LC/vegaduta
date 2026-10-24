---
title: Tool description: browser_totp
description: The description of the browser_totp tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Returns the current 6-digit two-factor (TOTP) code for a vault secret that holds the account's authenticator seed, valid for the seconds reported. Use it only when a site you are logged into asks for a verification code; type the code with browser_type right away. The seed itself is never shown.
