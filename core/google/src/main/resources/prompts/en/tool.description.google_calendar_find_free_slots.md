---
title: Tool description: google_calendar_find_free_slots
description: The description of the google_calendar_find_free_slots tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Finds open appointment slots on the connected Google account's primary calendar between two dates, inside working hours, skipping busy time. Read-only: it books nothing. Book a returned slot with google_calendar action 'create_event'. Slots start no earlier than now.
