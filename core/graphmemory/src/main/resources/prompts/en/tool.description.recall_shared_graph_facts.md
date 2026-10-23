---
title: Tool description: recall_shared_graph_facts
description: The description of the recall_shared_graph_facts tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Looks up relational facts about a named entity from the SHARED team memory — facts that other agents on this team have remembered about this user's connections and relationships. Use this to access knowledge that a colleague agent already discovered (e.g. "Priya WORKS_AT Acme Corp" written by the onboarding agent is now visible to the support agent). Only available when team memory sharing is enabled for this agent.
