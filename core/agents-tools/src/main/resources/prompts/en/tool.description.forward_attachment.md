---
title: Tool description: forward_attachment
description: The description of the forward_attachment tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Forwards an image or audio clip that a PRIOR call to generate_image or speak in THIS conversation actually produced to a channel/recipient. attachmentUrl MUST be the exact imageUrl/audioUrl string a real tool result returned earlier in this conversation - never construct, guess, or paraphrase one (the same rule the speak tool's own description gives for the URL it returns; this tool enforces it server-side too by only resolving real stored artifacts). If no such URL exists yet because nothing was actually generated, call generate_image or speak FIRST, then forward the URL it returns - do not call this tool on a made-up reference. channel is one of the connected channels (e.g. 'WHATSAPP', 'SLACK', 'DISCORD', 'TELEGRAM', 'EMAIL') and to is the recipient in that channel's own address form (a phone number, a Slack channel id, an email address, etc.) - ask the user which channel/recipient if it isn't already clear from the conversation. Video attachments are not forwardable yet.
