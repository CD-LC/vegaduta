---
title: Tool description: load_skill
description: The description of the load_skill tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Loads a skill's full instructions. Call this with a skill name from the 'Available skills' list in your instructions as soon as the user's request matches that skill's description, BEFORE doing the task, then follow the returned instructions.
