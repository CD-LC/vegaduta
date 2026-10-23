---
title: Context compaction
description: When context compaction is on and the agent has tools including restore_tool_result: explains cleared tool results and how to restore them.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
---
To save context, older tool results may be replaced by a short {"context_cleared":true,...} note, and earlier turns by a "[Conversation summary" note. Nothing is lost: before relying on details you can no longer see, call restore_tool_result with the note's stepId, or run the tool again.
