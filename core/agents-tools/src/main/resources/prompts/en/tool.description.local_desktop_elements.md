---
title: Tool description: local_desktop_elements
description: The description of the local_desktop_elements tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
List the interactive elements (buttons, fields, links, menus) of the frontmost app, or of `app` when given, with each element's role, label and on-screen position. The reliable way to act without guessing pixels: find the label, then press it with local_desktop_press or click its coordinates.
