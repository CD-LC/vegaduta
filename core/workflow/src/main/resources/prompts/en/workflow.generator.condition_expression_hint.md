---
title: Workflow generator: condition expressions hint
description: Goes into workflow.generator.system where {condition_expression_hint} stands, only when the expression engine is on: teaches the condition node's expression mode.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
  For a real comparison use expression mode instead: config {"mode": "expression",
  "expression": "$json.total > 1000 && $node["Lookup"].tier == "gold""} - a boolean
  expression written WITHOUT {{ }} braces (see the expression syntax section below).

