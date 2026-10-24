---
title: Tool description: local_desktop_activate
description: The description of the local_desktop_activate tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: approval_note
placeholder.approval_note: A sentence the platform appends when each call is shown to the user for approval before it runs; empty when approval is off
maxLength: 4000
---
Bring the named application to the front so follow-up typing and clicks land in it.{approval_note}
