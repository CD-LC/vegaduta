---
title: Tool description: channel_send_message
description: The description of the channel_send_message tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Sends a message on one of the tenant's connected channels: whatsapp (auto-picks the connected provider), telegram, slack, discord, teams, email, messenger, instagram or x. 'to' uses the channel's own address form (phone number, chat/channel id, conversation id, email address). The send runs through the platform's approval gate - it may return pending with a request id instead of sending immediately, in which case a human must approve it first. Check channels_overview first if unsure which channels are connected.
