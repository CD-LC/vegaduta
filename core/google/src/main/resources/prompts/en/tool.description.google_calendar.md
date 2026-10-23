---
title: Tool description: google_calendar
description: The description of the google_calendar tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads and creates events on the connected Google account's primary calendar. Actions: 'list_upcoming' (upcoming events from now), 'create_event' (requires summary, start, end as RFC3339 date-times e.g. 2026-07-10T15:00:00-07:00, optional attendees as a comma-separated list of email addresses).
