---
title: Tool description: http_request_write
description: The description of the http_request_write tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Sends data to a public REST API, webhook or endpoint over https with POST, PUT, PATCH or DELETE and a JSON, text or form body, and returns the status, response headers and body. It changes state at the destination and needs human approval. For authentication pass the NAME of a stored vault secret in 'auth' (never the secret itself); it is added server-side and never shown to you. Private/internal addresses are refused. To only read data use http_request.
