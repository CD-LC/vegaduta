---
title: Tool description: improve_skill
description: The description of the improve_skill tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Proposes an improvement to an EXISTING custom skill when you found its instructions wrong, incomplete or out of date while using it. Send the complete improved instructions, not a diff. The live skill keeps running unchanged: your version is staged for a workspace admin to review and apply, so tell the user it won't take effect until then. For a method that doesn't exist yet, use propose_skill instead.
