---
title: Tool parameter: http_request.safe.authType
description: The description of the 'authType' parameter in the input schema of the http_request.safe tool.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 1000
---
How the secret is sent: bearer (default) = Authorization: Bearer; basic = secret is user:password; header = the whole value of authHeader.
