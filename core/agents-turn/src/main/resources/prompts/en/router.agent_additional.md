---
title: Extra-specialist question
description: Per-candidate YES/NO question put to the decision engine when a task may need more than one specialist agent; YES adds that agent as a delegation target. The placeholder is the candidate agent's description.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 2000
placeholders: agent!
placeholder.agent: The candidate agent's name and purpose, as shown to the decision engine
---
The user's task may need more than one specialist. Would the agent "{agent}" meaningfully contribute to completing it? YES only if the task plausibly needs that agent's specific capabilities; NO if it is unrelated or only tangentially connected.
