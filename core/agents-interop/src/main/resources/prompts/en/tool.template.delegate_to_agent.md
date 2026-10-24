---
title: Tool template: delegate_to_agent
description: The description of the delegate_to_agent tool as sent to the model, a template the tool fills in when it is built (placeholders).
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: true
placeholders: name!, specialty!
placeholder.name: The collaborator agent's name
placeholder.specialty: Punctuation and the collaborator's own description: "." when it has none, otherwise ": <description>."
maxLength: 4000
---
Delegates a specific question to "{name}", a specialist agent{specialty} Use this when part of the user's request falls squarely in this specialist's area rather than your own - ask it a focused, self-contained question and use its answer. It will NOT see this conversation's history, only the exact question you send plus, if you provide one, a short context summary.
