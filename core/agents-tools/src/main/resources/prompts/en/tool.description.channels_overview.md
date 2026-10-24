---
title: Tool description: channels_overview
description: The description of the channels_overview tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists every messaging channel on this tenant and its live state: connected or not, which provider/account is behind it, how many paired conversations it has, when the last message arrived, and which agent inbound messages route to. Use this before channel_send_message to check the channel is actually connected, or to answer questions like 'which channels are we on' or 'where do Telegram messages go'.
