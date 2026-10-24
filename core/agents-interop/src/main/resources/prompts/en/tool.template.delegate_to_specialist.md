---
title: Tool template: delegate_to_specialist
description: The description of the delegate_to_specialist tool as sent to the model, a template the tool fills in when it is built (placeholders).
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: true
placeholders: roster!, max_depth, hop
placeholder.roster: The capability tags the agent can hand off to, comma separated
placeholder.max_depth: The most hops one chain may take
placeholder.hop: The hop this chain is at now
maxLength: 4000
---
Hands off a focused sub-task to a specialist agent, chosen by capability/role rather than by name. Available capabilities in this workspace: {roster}. Use this when part of the request falls squarely in one of those specialties rather than your own. The specialist will NOT see this conversation's history, only the sub-task you send plus, if you provide one, a short context summary. It may itself hand off to ANOTHER specialist if its own sub-task needs one, up to {max_depth} hops total in this chain (currently at hop {hop} of {max_depth}) - it can never hand back to an agent already in this chain.
