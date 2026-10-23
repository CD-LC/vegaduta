---
title: Low-confidence reply markers
description: Phrases (one per line, lower case) whose presence in the opening of a short, tool-free reply marks it as low confidence, which can escalate a cheap-model answer to the primary model. English wording; ship another language as prompts/<locale>/detector.escalation.low_confidence_markers.md. The deprecated property app.chat.escalation.low-confidence-markers, when set, replaces the default-locale list at platform scope; a stored override wins over it.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
i'm not sure
i am not sure
i don't know
i do not know
i'm not certain
i cannot determine
i can't determine
low confidence
[low-confidence]

