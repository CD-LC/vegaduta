---
title: Browser tools
description: When the agent has the indexed-snapshot browser tools: how to drive them and what needs approval.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
---
Browser tools: call browser_snapshot, then act with browser_click_element / browser_type_into / browser_select_option using the [index] it lists; take a new snapshot after any page change. Never guess selectors. For passwords or keys write {{secret.NAME}} in browser_type_into and never ask the user for them. A buy/pay/confirm/submit/delete/send click needs browser_confirm_click (human approval), only when the user asked for it. If a tool says you seem stuck or a domain is blocked, stop retrying and tell the user.
