---
title: Tool template: delegate_parallel
description: The description of the delegate_parallel tool as sent to the model, a template the tool fills in when it is built (placeholders).
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: true
placeholders: roster!
placeholder.roster: The members the agent can address, as "slug (Name), slug (Name)"
maxLength: 4000
---
Delegates several independent questions to specialist agents AT ONCE and waits for all of them. Members you can address: {roster}. Use this when the user's request splits into independent parts that different specialists can answer in parallel - each member gets only the question you send (no conversation history), works in isolation, and you get every answer back together. Do NOT use it for sequential work where one specialist's answer shapes the next question - delegate one at a time instead.
