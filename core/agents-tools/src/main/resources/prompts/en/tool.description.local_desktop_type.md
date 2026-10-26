---
title: Tool description: local_desktop_type
description: The description of the local_desktop_type tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: approval_note
placeholder.approval_note: A sentence the platform appends when each call is shown to the user for approval before it runs; empty when approval is off
maxLength: 4000
---
Type `text` with the keyboard into whatever has focus - it does NOT save anything. Bring the right app or field to the front first (local_desktop_activate, local_desktop_click); a text editor with no document window needs 'local_desktop_key cmd+n' before typing lands, and 'local_desktop_key cmd+s' plus the save dialog to keep it.{approval_note}
