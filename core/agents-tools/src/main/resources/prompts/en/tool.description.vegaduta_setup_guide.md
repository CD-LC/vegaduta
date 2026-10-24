---
title: Tool description: vegaduta_setup_guide
description: The description of the vegaduta_setup_guide tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Finds how to set something up on this VegaDuta install and where. Call it BEFORE answering when the user asks how to connect, configure, enable, install or integrate anything (MCP servers, channels, Google, model providers, knowledge, API keys, SSO, tools, workflows...), asks a how-do-I question about the platform, or when you lack a tool the request needs. It returns the console page link, the steps, this install's live status for that topic, what else must be on, and passages from the platform docs. For REST endpoints use vegaduta_api_guide instead. action=help (default) matches setup topics and docs to the query; action=docs searches only the docs; action=topic returns one topic by id.
