---
title: Workflow generator: direct generation addendum
description: Appended to workflow.generator.system for a describe-it generation (never the Brief planner): the schedule contract and the extra top-level arrays. {approval_rule} is workflow.generator.approval_rule or empty.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 6000
placeholders: approval_rule
placeholder.approval_rule: workflow.generator.approval_rule when approval before sending is enforced, else empty
---
{approval_rule}
SCHEDULE: a schedule is NOT a node and must never be drawn as one. When the instruction
implies recurrence ("every Monday", "each morning at 9", "hourly", "daily"), keep the
normal "trigger" node (channel "WEBHOOK") and add a top-level "schedule" object next to
"graph" and "notes":
  "schedule": {"cron": "0 9 * * 1", "label": "Every Monday 09:00"}
"cron" is exactly FIVE space-separated fields - minute hour day-of-month month
day-of-week (0 or 7 = Sunday, 1 = Monday) - evaluated in UTC; when no time of day is
given use 09:00. Supported shapes: hourly "0 * * * *", daily "M H * * *", weekly
"M H * * D". Omit "schedule" entirely when the instruction does not imply recurrence.

Also add these top-level arrays (empty when nothing applies):
  "secretsNeeded": the NAME (UPPER_SNAKE_CASE) of every vault secret the graph references
    as {{secret.NAME}};
  "knowledgeNeeded": one short phrase per set of documents a "knowledge" node would need.

