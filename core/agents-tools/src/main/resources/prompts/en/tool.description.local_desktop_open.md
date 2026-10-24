---
title: Tool description: local_desktop_open
description: The description of the local_desktop_open tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: approval_note
placeholder.approval_note: A sentence the platform appends when each call is shown to the user for approval before it runs; empty when approval is off
maxLength: 4000
---
Open `target` - an app name, a file path or a URL - in the laptop's normal handler, or in `application` when given.{approval_note}
