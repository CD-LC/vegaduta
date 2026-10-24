---
title: Tool description: sarvam_transcribe
description: The description of the sarvam_transcribe tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Transcribes a short audio clip (base64-encoded WAV/MP3/etc., not a URL) to text using Sarvam AI, with support for Indian languages plus English. mode='translate' transcribes AND translates the result to English in one call (e.g. a Hindi voice clip becomes English text directly); mode='transcribe' (default) keeps the original spoken language. language_code is optional - omit it to auto-detect the spoken language.
