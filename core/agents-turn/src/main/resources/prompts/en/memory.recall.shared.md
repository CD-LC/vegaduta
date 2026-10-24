---
title: Recalled memory: shared by the team
description: Every turn of an agent that shares memory with its tenant and has recalled shared facts: the line that frames the list.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: facts!
placeholder.facts: One "- fact" line per recalled shared fact
---
Known facts about this user shared by other agents on this team (use if relevant, don't force them into unrelated answers):
{facts}
