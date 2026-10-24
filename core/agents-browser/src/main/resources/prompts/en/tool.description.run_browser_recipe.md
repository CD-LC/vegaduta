---
title: Tool description: run_browser_recipe
description: The description of the run_browser_recipe tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Replays a saved browser recipe (a recorded, verified sequence of browser steps) without step-by-step AI, in this conversation's browser session. Omit recipeId to list the recipes you can run. If a step fails verification the replay stops and reports which one - then continue by hand with the browser_* tools from the current page. Steps that submit or commit something are not run until confirmIrreversible is true.
