---
title: Tool description: local_desktop_key
description: The description of the local_desktop_key tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: approval_note
placeholder.approval_note: A sentence the platform appends when each call is shown to the user for approval before it runs; empty when approval is off
maxLength: 4000
---
Press a named key (return, enter, tab, space, delete, escape, left, right, down, up, f1-f12) or a modifier combo like 'cmd+s' (save), 'cmd+n' (new document), 'cmd+a', 'ctrl+c'. Modifiers: cmd/command, ctrl, alt/option, shift.{approval_note}
