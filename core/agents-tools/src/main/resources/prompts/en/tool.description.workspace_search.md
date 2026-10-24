---
title: Tool description: workspace_search
description: The description of the workspace_search tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: root!
placeholder.root: The label of the projects folder the tool is rooted at (the platform fills it in)
maxLength: 4000
---
Searches the workspace like a find-in-files: file names that contain the query, then lines of text files that contain every word of it (case-insensitive). Use it to find a document or a passage the person attached or mentioned, then workspace_read the file. PDFs the person attached have their text in a sibling '<name>.pdf.txt' file. Paths are relative to the person's projects folder ({root}); start them with the folder the person attached. Nothing outside it can be reached.
