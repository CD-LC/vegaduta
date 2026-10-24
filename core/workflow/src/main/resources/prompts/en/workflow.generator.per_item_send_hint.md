---
title: Workflow generator: per-item messaging hint
description: Goes into workflow.generator.system where {per_item_send_hint} stands, only when the expression engine is on: how to message each item of a list.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
  Messaging each item of a list: make an "output" node the single per-item step of a "loop"
  (or "fan_out") and take the destination from the item, e.g. config {"channel": "whatsapp",
  "whatsappTo": "{{ $json.phone }}"} or {"channel": "email", "emailTo": "{{ $json.email }}"}
  - inside a loop this node's input is the current item. For WhatsApp, write the text to
  send in "whatsappMessage" (e.g. "Hi {{ $json.name }}, invoice {{ $json.number }} is due.");
  leave it out to send the node's input as it is.

