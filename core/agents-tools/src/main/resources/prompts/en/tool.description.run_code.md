---
title: Tool description: run_code
description: The description of the run_code tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Runs a short snippet of code in a fresh, disposable sandbox and returns stdout/stderr/exit code. Use this to verify logic, run a calculation, test a regex, or check a script actually works before telling the user it does - do not use this for anything that needs to persist state across calls (each call gets a brand-new sandbox) or that touches the tenant's real systems. language must be one of 'python', 'node', or 'bash'.
