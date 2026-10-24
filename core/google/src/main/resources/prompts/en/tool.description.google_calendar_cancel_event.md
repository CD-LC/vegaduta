---
title: Tool description: google_calendar_cancel_event
description: The description of the google_calendar_cancel_event tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Cancels (deletes) one event on the connected Google account's primary calendar by its event id (from google_calendar list_upcoming or create_event). Needs human approval. Set notify_attendees true to have Google email every attendee the cancellation.
