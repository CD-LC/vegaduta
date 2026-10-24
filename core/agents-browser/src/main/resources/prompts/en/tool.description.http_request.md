---
title: Tool description: http_request
description: The description of the http_request tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Makes an HTTP request to any public REST API, webhook or endpoint (GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS) with custom headers, query parameters and a JSON, text or form body, and returns the status code, response headers and the response body (parsed as JSON when the API returns JSON). Use it to call, query, integrate with or send data to a web service, API or webhook, to post a payload, or to fetch raw JSON/text data from a URL. For authentication, put a reference to a vault secret in a header instead of a literal key - e.g. "Authorization": "Bearer {{secret:MY_API_KEY}}" - the value is filled in server-side and never shown to you, and only for hosts the secret is allowed to be sent to in the vault; never ask the user to paste a key into the chat. Only public http/https URLs work; internal/private network addresses are refused. Maximum timeout 30s.
