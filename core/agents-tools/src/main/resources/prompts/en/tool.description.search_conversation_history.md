---
title: Tool description: search_conversation_history
description: The description of the search_conversation_history tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Searches this tenant's persisted channel conversation history - inbound customer messages and outbound replies across WhatsApp, Telegram, Slack, Discord, Email, Teams, X and Meta, in ANY past session, not just this one. Use it for questions like 'what did this customer ask before', 'have we ever discussed X', or 'find past messages mentioning Y'. Matches a literal substring of the message text (case-insensitive). Each hit includes the channel, sender, direction, author, message text and timestamp, newest first.
