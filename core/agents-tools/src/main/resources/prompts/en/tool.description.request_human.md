---
title: Tool description: request_human
description: The description of the request_human tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Escalates THIS conversation to a human team member. Use this when the user explicitly asks to speak to a person, when you are unable to help after genuinely trying, or when the request is sensitive enough (a complaint, a refund, something you should not decide alone) that a human should take over. reason is shown to the operator picking this up, so make it a short, specific summary of why a human is needed - not a generic phrase. After calling this tool, tell the user (in their language, briefly) that a team member has been notified, using the tool result's own wording as your guide - do not promise a response time this tool does not state.
