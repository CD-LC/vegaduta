---
title: Tool description: channel_list_sessions
description: The description of the channel_list_sessions tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists channel conversations (sessions), newest activity first: the sender's external id, whether the sender is paired (approved) or still pending pairing approval, the agent currently bound to the conversation, and the last message preview. Optionally filter to one channel. Use this to find a sessionId for channel_session_action, or to answer 'who has been messaging us'.
