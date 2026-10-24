---
title: Tool description: local_shell.unattended
description: The description of the local_shell.unattended tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: environment!
placeholder.environment: One sentence on where commands run and what they can reach (the runner's own text)
maxLength: 4000
---
Runs any shell command (build, script, file processing, anything) and returns its output. On this install the calls run without waiting for approval (the owner trusted the sandboxed terminal), so still say in plain words what the command does first, and prefer local_shell_read for looking around. {environment} stdout and stderr come back together with the exit code.
