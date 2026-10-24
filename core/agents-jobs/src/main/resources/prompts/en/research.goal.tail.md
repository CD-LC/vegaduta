---
title: Research goal: closing instructions
description: The last lines of the goal of every research run: call deep_research first, never answer from memory or with placeholder sources, then return the JSON result. The last thing the model reads before it answers, so keep it short.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 300
---
Start by calling deep_research. Never answer from memory or with example or placeholder sources: every source must be one the tool returned.
Follow your research procedure and return the JSON result.
