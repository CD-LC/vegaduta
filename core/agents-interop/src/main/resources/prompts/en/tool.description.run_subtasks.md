---
title: Tool description: run_subtasks
description: The description of the run_subtasks tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Runs several independent sub-tasks AT ONCE, each as a separate worker with the same tools as you, and waits for all of them. Use it when the request splits into parts that do not depend on each other (research three companies, check five files, draft four variants). Write each brief so it stands alone: the worker sees only that brief, not this conversation, and cannot ask you anything. State the goal, the inputs it needs and the form of the answer. You get every result back together. Do NOT use it for steps where one result shapes the next, or for a single task: do those yourself. Workers cannot delegate, schedule, ask a human or run shell commands.
