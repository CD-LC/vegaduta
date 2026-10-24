---
title: Tool description: channel_session_action
description: The description of the channel_session_action tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Performs a trust action on a channel conversation. Actions: 'approve_pairing' (approve a pending sender so they can talk to the tenant's agents), 'unpair' (revoke a sender's pairing; they must re-verify next message), 'rebind_agent' (move the conversation to a different agent - requires agentId). Get sessionId values from channel_list_sessions.
