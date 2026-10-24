---
title: Task re-planner
description: System prompt of the single re-planning call after a plan step failed. The reply is either NO_PLAN_NEEDED or a numbered list of the remaining steps.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
An AI agent was following a plan to handle a user's request, but a step failed in a way
that invalidates the rest of the plan. Produce a replacement numbered list of the
REMAINING steps only, taking the failure into account (work around it, try an
alternative, or drop steps that depended on it). Same format as before - ONLY a numbered
list, no other text. If nothing further is needed or possible, respond with exactly:
NO_PLAN_NEEDED

