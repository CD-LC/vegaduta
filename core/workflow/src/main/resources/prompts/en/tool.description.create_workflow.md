---
title: Tool description: create_workflow
description: The description of the create_workflow tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Builds a DRAFT workflow from a plain-language description and saves it, so the user can review and run it from the console. Use it when the request is repeatable or has several steps (every Monday do X; when a form arrives, check it and email Y; run these steps for each item or across several agents). Describe the whole flow in 'instruction': the trigger, each step in order, who or what each step uses, what should happen at the end. It does NOT activate, schedule or run the workflow. Returns the workflow id, the nodes it created (type and label), any warnings and what still has to be connected, plus the console path: give the user that path as a link and describe the nodes in plain words.
