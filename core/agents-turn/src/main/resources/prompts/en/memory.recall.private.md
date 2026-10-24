---
title: Recalled memory: this agent
description: Every turn that recalls facts this agent remembered about the user: the line that frames the list.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: facts!
placeholder.facts: One "- fact" line per recalled fact
---
Known facts about this user from previous conversations (use if relevant, don't force them into unrelated answers):
{facts}
