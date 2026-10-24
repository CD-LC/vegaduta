---
title: Delegation context: collaborator
description: A collaborator agent called by another agent: frames the summary the caller forwarded.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: context!
placeholder.context: The summary the delegating agent passed on
---
Context forwarded by the delegating agent (a short summary, not the full conversation - use it only if relevant to the question below):
{context}
