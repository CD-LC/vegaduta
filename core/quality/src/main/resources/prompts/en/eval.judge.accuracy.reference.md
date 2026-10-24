---
title: Accuracy judge: reference answer block
description: Block inserted into the accuracy judge prompt when the case has a known-good reference answer. Starts and ends with a line break.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: reference!
placeholder.reference: The reference answer
---

REFERENCE ANSWER (known-good, authored by the agent's owner - treat as ground truth):
{reference}

