---
title: Workflow generator: expression language
description: Goes into workflow.generator.system where {expression_block} stands, only when the expression engine is on: teaches the {{ expression }} language. {functions} is the live list of built-in functions by category, so it cannot drift from what the engine accepts.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 8000
placeholders: functions!
placeholder.functions: The built-in functions, one category per line: '  Category: name, name'
---
Expressions: node config text fields may embed {{ expression }} to read data from earlier
steps. The plain forms still work and are the right choice for simple cases: "{{input}}" is
this node's raw input and "{{fieldName}}" is a top-level field of the input when it is a JSON
object. Use the richer syntax only when the instruction needs data from a step other than
the immediately previous one, array indexing, or a computed value:
  - $node["Label"].path  output of an earlier node, addressed by its exact "label" (the
    node must be upstream of this one), e.g. {{ $node["Fetch Order"].items[0].price }}
  - $json.path           this node's own input, parsed when it is JSON
  - $trigger.path        the payload that started the run (webhook body, message, ...)
  - $item, $index        the current element and position inside a loop / fan_out body
  - operators: + - * / %  == != < > <= >=  && || !  a ? b : c  and ?? (default when null)
  - access: a.b, a?.b, a["k"], a[0], a[-1] (last element); method form x.fn(args) works
  - arrow functions only as function arguments: items.filter(i => i.qty > 0)
A missing path yields an empty value, not an error. Text outside {{ }} stays literal. Only
these fields are evaluated: agent inputMapping.query, code template, http url / body /
header and query values, tool argsJson, output destinations (emailTo, fileName, ...),
bulk_email subject / body, crm_lead fields, and the condition "expression". Never put an
expression in any other field, in an id, or in "label". Do not use expressions for secrets:
keep the vault-reference form ({{secret.NAME}}) for API keys.
Built-in functions (call as fn(args) or x.fn(args)):
{functions}

