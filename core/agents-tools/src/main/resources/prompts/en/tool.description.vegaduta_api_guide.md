---
title: Tool description: vegaduta_api_guide
description: The description of the vegaduta_api_guide tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Looks up this VegaDuta install's REST API in its real OpenAPI spec. Use it whenever the user asks how to do something with the API, which endpoint to call, or wants data the API can return. action=search finds endpoints by words; action=describe returns the parameters, request body and response of one endpoint and a ready json_ui apiCall block that shows the live result to the user (GET runs by itself, POST/PUT/PATCH/DELETE wait for the user to press Send). Prefer showing the live result over describing it. The full spec is at /api/openapi.json.
