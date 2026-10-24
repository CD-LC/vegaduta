---
title: Tool description: local_shell_read
description: The description of the local_shell_read tool, as sent to the model in the tool list. A template: the placeholders are filled in by the platform when the tool is built.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: true
placeholders: environment!
placeholder.environment: One sentence on where commands run and what they can reach (the runner's own text)
maxLength: 4000
---
Runs a read-only inspection command without asking: ls, cat, head, tail, wc, grep, find, sort, cut, diff, jq, git status/log/diff/show, date, uname, df, du, ps, which, and pipes between them. No redirects, no $( ) or backticks, no ; && or &. Anything else is refused: use local_shell for that. {environment} stdout and stderr come back together with the exit code.
