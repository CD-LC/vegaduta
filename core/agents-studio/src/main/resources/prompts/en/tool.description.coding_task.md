---
title: Tool description: coding_task
description: The description of the coding_task tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Delegate a coding change to an isolated sandbox: it clones a public https git repository, edits the code to do what the instruction says, runs the test command and iterates until the tests pass, then returns the diff and a test summary. The user must confirm before it starts, and must approve the diff on the Coding tasks page before anything counts as applied - you cannot approve it yourself. Takes minutes; if it is still running you get a task id to report.
