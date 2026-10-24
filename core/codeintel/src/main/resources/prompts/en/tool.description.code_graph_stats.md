---
title: Tool description: code_graph_stats
description: The description of the code_graph_stats tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Returns repo-wide code graph statistics: total indexed files and symbols, edge counts grouped by edge type (e.g. calls/inherits), files grouped by parser engine, and call edges grouped by resolution confidence (local/global/ambiguous). Use this to check whether a repo's code graph is built/populated before trusting other graph tools' results, or to get a quick overview of an unfamiliar repo's size and shape. Requires the repo_id.
