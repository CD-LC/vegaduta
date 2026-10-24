---
title: Tool description: code_impact
description: The description of the code_impact tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Computes the transitive blast radius of changing a symbol or file - everything that (directly or indirectly) calls it, inherits from it, or imports it, grouped by level. Use this before proposing a refactor, rename, or migration to understand what a change could affect. Requires the repo_id.
