---
title: Prompt-based structured output instruction
description: Paragraph added to the system message when a model that cannot enforce a JSON schema is asked for structured output.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
placeholders: schema!
placeholder.schema: The JSON Schema the reply must satisfy
---
Reply with ONLY one JSON value that is valid for the JSON Schema below. No markdown code fences, no commentary before or after.
JSON Schema: {schema}
