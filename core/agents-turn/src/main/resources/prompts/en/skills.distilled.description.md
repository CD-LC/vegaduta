---
title: Distilled skill: description
description: One-line description of a distilled skill, shown in the skill list and used to decide when to load it. Cut to 200 characters by the platform.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 2000
placeholders: runs!, sequence!
placeholder.runs: Number of successful runs that followed the sequence
placeholder.sequence: The tool sequence, tool names joined by "->"
---
Distilled from {runs} successful runs: {sequence}.
