---
title: Tool description: schedule_task
description: The description of the schedule_task tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Schedule a recurring task: a prompt this agent runs by itself at set times (for example weekdays at 08:00) and delivers in-app or on a channel the user named. The user must confirm before it is created. Use the user's own timezone. For a one-time reminder set maxRuns to 1. Leave delivery and deliveryTo out unless the user named a channel and a recipient: the default is in-app.
