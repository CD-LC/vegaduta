---
title: Distilled skill: purpose section
description: First section of the body of a skill the platform distils from repeated successful tool sequences of an agent. The skill is staged disabled and a person enables it; once enabled, its body is guidance the agent loads. Ends with a blank line before the next section.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 2000
placeholders: runs!, sessions!
placeholder.runs: Number of successful runs that followed the sequence
placeholder.sessions: Number of distinct sessions those runs came from
---
## Purpose

This procedure was distilled from {runs} successful runs of this agent (across {sessions} separate sessions) that all followed the same tool sequence. It is a record of what demonstrably worked, not a guess.


