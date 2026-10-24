---
title: Refusal: output guardrail
description: Replaces an answer an agent guardrail withheld; it is stored as the answer.
risk: USER_VISIBLE
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: guardrail!
placeholder.guardrail: Display name of the guardrail that blocked the answer (for example "content guardrail")
---
The agent's response was withheld: it was blocked by this agent's {guardrail} check. Please try rephrasing your request.
