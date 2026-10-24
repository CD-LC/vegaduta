---
title: Swarm worker brief
description: The structured brief each worker of a verified multi-agent team receives for its sub-task: the overall objective (when there is one), the sub-task, and the required output, constraints and uncertainty reporting. Keep the three labelled lines at the end: the verifier checks the results against this shape.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 3000
placeholders: objective!, subtask!
placeholder.objective: The objective block (swarm.brief.objective), or empty when the team has no goal
placeholder.subtask: The question for this worker
---
{objective}YOUR SUB-TASK: {subtask}

REQUIRED OUTPUT: a concise plain-text answer that leads with the direct answer, then the key evidence or sources.
CONSTRAINTS: answer only this sub-task; do not invent facts or present assumptions as facts.
UNCERTAINTY: report uncertainty explicitly - state what you could not verify and your confidence (high/medium/low).
