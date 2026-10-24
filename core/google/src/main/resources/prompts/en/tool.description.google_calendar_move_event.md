---
title: Tool description: google_calendar_move_event
description: The description of the google_calendar_move_event tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Moves one event on the connected Google account's primary calendar to a new start and end (reschedule an appointment); title, attendees and description are kept. Needs human approval. Find a free time first with google_calendar_find_free_slots. Set notify_attendees true to have Google email every attendee the new time.
