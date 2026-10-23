---
title: Tool description: sarvam_transliterate
description: The description of the sarvam_transliterate tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Converts text from one script to another while PRESERVING PRONUNCIATION, e.g. 'namaste' -> 'नमस्ते' or 'नमस्ते' -> 'namaste'. Use this instead of sarvam_translate when the goal is script conversion (same words, different alphabet), not meaning conversion (different words). source_language_code and target_language_code are BCP-47 codes, e.g. 'en-IN', 'hi-IN', 'ta-IN'.
