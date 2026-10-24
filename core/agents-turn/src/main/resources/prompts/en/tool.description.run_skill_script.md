---
title: Tool description: run_skill_script
description: The description of the run_skill_script tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Runs a script bundled with a skill you have already loaded (a .py or .java file listed by load_skill) in an isolated sandbox and returns its result. Use it only when the skill's instructions tell you to run that script. The sandbox has no network access.
