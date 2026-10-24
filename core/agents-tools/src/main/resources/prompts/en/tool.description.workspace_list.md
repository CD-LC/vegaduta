---
title: Tool description: workspace_list
description: The description of the workspace_list tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: root!
placeholder.root: The label of the projects folder the tool is rooted at (the platform fills it in)
maxLength: 4000
---
Lists the files and folders in a workspace folder (dependency and build output folders such as node_modules, target and .git are skipped). Paths are relative to the person's projects folder ({root}); start them with the folder the person attached. Nothing outside it can be reached.
