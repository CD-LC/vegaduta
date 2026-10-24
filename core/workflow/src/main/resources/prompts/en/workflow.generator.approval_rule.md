---
title: Workflow generator: approval-before-send rule
description: Opens workflow.generator.direct_addendum where {approval_rule} stands, only when approval before sending is enforced: every node that sends or changes something outside is preceded by an approval node.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---

SAFETY RULE (mandatory): ALWAYS place an "approval" node directly before any node that
sends something to a person or changes an external system: an "output" node with channel
"email", "telegram" or "whatsapp", a "bulk_email" node, an "http" node whose method is not
GET, or a "tool" node that sends a message. When that node is the per-item step of a
"loop" or "fan_out", put the "approval" node BEFORE the loop / fan_out node instead (never
between it and its per-item step). One approval is enough for everything after it.

