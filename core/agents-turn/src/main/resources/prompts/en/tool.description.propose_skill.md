---
title: Tool description: propose_skill
description: The description of the propose_skill tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Saves a reusable method you just worked out as a new skill, so future conversations can load it instead of rediscovering it. Use it only after finishing a multi-step task that is likely to come up again, or when the user asks you to remember how to do something. Write general instructions, never the user's private data, names, secrets or one-off details. The skill is saved as a draft: a workspace admin must review and enable it before any agent can use it, so tell the user that.
