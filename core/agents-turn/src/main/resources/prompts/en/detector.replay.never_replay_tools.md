---
title: Never-replay tools
description: Exact tool names (one per line) that are never treated as replay-safe even when the tool classification says non-durable (http_request can POST). The platform always applies the shipped list in addition to an override, so an override can add names but never remove one.
risk: SECONDARY_CALL
securityCritical: true
disableable: false
retired: false
maxLength: 4000
---
http_request
http_request_write

