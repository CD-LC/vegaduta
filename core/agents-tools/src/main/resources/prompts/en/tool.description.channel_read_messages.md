---
title: Tool description: channel_read_messages
description: The description of the channel_read_messages tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads recent inbound messages received on any of this tenant's connected channels, newest first: whatsapp, telegram, slack, discord, teams, email, messenger, instagram or x. Each result includes the sender's external id, the message text, and when it was received. Use this for questions like 'what did people say on WhatsApp' or 'read recent Slack messages'.
