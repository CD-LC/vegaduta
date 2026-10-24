---
title: Tool description: workspace_write
description: The description of the workspace_write tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: root!
placeholder.root: The label of the projects folder the tool is rooted at (the platform fills it in)
maxLength: 4000
---
Creates or replaces a whole text file in the workspace (missing folders are created). Prefer workspace_edit for a small change to an existing file. Paths are relative to the person's projects folder ({root}); start them with the folder the person attached. Nothing outside it can be reached.
