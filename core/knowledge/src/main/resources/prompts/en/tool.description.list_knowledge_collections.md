---
title: Tool description: list_knowledge_collections
description: The description of the list_knowledge_collections tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Lists the knowledge collections available to search, each with a name and description explaining what it contains. Call this FIRST when you're not sure which knowledge collection is relevant to the user's question, then pass the collection's id as the optional collection_id parameter to search_knowledge_base to scope your search to just that collection.
