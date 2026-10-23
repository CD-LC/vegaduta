---
title: Context compaction (no restore tool)
description: The same, when restore_tool_result is not on the turn's tool list: the model is told to run the tool again instead.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
---
To save context, older tool results may be replaced by a short {"context_cleared":true,...} note, and earlier turns by a "[Conversation summary" note. Before relying on details you can no longer see, run the tool again.
