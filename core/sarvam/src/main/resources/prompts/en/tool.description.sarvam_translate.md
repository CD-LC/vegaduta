---
title: Tool description: sarvam_translate
description: The description of the sarvam_translate tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Translates text into an Indian language (or English) using Sarvam AI. Preserves meaning, not just script - use this instead of translating yourself when the tenant has Sarvam configured, since it's a dedicated translation model. target_language_code is a BCP-47 code, e.g. 'hi-IN' Hindi, 'ta-IN' Tamil, 'bn-IN' Bengali, 'mr-IN' Marathi, 'te-IN' Telugu, 'kn-IN' Kannada, 'gu-IN' Gujarati, 'ml-IN' Malayalam, 'pa-IN' Punjabi, 'od-IN' Odia, 'en-IN' English (plus several more scheduled Indian languages). source_language_code is optional - omit it to auto-detect.
