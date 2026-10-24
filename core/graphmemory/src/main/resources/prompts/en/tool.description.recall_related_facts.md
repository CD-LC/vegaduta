---
title: Tool description: recall_related_facts
description: The description of the recall_related_facts tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Looks up facts CONNECTED to a named entity (a person, place, company, or thing) from what you've remembered about this user - not just facts similar in wording, but facts relationally linked (e.g. asking about "Priya" can surface "Priya's manager is Raj" even if the current message never mentions Raj). Use this when the user asks about a specific named thing and a simple keyword match might miss an indirect connection.
