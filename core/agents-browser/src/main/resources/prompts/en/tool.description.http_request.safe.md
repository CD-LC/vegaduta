---
title: Tool description: http_request.safe
description: The description of the http_request.safe tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads from a public REST API or URL over https with GET, HEAD or OPTIONS, custom headers and query parameters, and returns the status, response headers and body (parsed as JSON when the API returns JSON). Use it to query, call or fetch raw JSON/text from a web service or API. For authentication pass the NAME of a stored vault secret in 'auth' (never the secret itself); it is added server-side and never shown to you. Private/internal addresses are refused. To send or change data use http_request_write.
