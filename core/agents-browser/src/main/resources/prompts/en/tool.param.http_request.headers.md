---
title: Tool parameter: http_request.headers
description: The description of the 'headers' parameter in the input schema of the http_request tool.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 1000
---
Request headers by name. A value may be a vault reference like {{secret:NAME}} (or {{secret.NAME}}), resolved server-side.
