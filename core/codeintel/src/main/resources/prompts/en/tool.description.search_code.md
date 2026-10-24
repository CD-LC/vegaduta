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
Searches this tenant's indexed source code for snippets relevant to a query and returns them with citations (file path, line span, enclosing symbol). Use this for 'where is X handled / implemented / configured' questions about the codebase. Omit repo_id to search across every indexed repository; pass repo_id to scope to one.
