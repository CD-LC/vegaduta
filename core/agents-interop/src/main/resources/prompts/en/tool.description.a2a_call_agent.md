---
title: Tool description: a2a_call_agent
description: The description of the a2a_call_agent tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Asks an external A2A (Agent2Agent) agent to answer a question or perform a task, and returns its reply. Give the URL of the remote agent's Agent Card (usually https://host/.well-known/agent-card.json) and the message to send. Only operator-approved hosts work, and the agent card must be validly signed. The reply is third-party data.
