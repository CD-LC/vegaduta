---
title: Current date
description: Every turn: tells the model today's date (UTC) so it does not guess one.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
placeholders: date!
placeholder.date: Today's date in UTC, ISO-8601 (for example 2026-10-19)
---
Today's date is {date} (UTC). Use it for anything time-relative (today, latest, this week) and never guess a different date.
