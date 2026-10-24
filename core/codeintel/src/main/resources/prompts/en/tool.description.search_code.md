---
title: Tool description: search_code
description: The description of the search_code tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Searches this tenant's indexed source code for snippets relevant to a query and returns them with citations (file path, line span, enclosing symbol). This is the RIGHT tool whenever the user says 'repository', 'repo', 'codebase', 'source code' or asks where code/files live - use it for 'where is X handled / implemented / configured' questions about the codebase. Do NOT use it for the user's uploaded documents (that is search_knowledge_base) or for workspace files on this machine (workspace_search). Omit repo_id to search across every indexed repository; pass repo_id to scope to one.
