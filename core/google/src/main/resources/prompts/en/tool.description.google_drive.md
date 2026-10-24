---
title: Tool description: google_drive
description: The description of the google_drive tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads and creates files in the connected Google Drive account. Only sees files this app created or that the user explicitly opened with it (drive.file scope), not the user's whole Drive. Actions: 'list' (optional name_contains filter), 'read' (requires file_id, works for plain text/binary files, not native Google Docs), 'create' (requires name and content, creates a plain text file).
