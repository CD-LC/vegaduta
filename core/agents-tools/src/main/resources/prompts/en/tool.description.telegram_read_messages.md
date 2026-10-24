---
title: Tool description: telegram_read_messages
description: The description of the telegram_read_messages tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads recent inbound Telegram messages received on this tenant's connected Telegram bot, across every chat, newest first. Use this to answer questions like 'what did people say on Telegram' or 'read all messages from telegram'. Each result includes the sender's chat id, the message text, and when it was received.
