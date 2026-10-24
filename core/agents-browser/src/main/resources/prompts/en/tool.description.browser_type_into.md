---
title: Tool description: browser_type_into
description: The description of the browser_type_into tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Types text into element [index] from the latest browser_snapshot, replacing its value. For credentials write {{secret.NAME}} (e.g. {{secret.PORTAL_PASSWORD}}): the real value is filled in server-side and never shown to you; never ask the user for a password. Secrets work only on domains an administrator allowed.
