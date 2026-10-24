---
title: Tool description: google_gmail
description: The description of the google_gmail tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads and sends email through the connected Gmail account. Actions: 'list' (optional gmail-syntax query, e.g. 'is:unread' or 'from:someone@example.com'), 'read' (requires message_id, returns the plain-text body), 'send' (requires to, subject, body). Reading requires the account to have granted read access; sending requires send access - either may be unavailable depending on what the tenant approved when connecting.
