---
title: Tool description: speak
description: The description of the speak tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Converts text to spoken audio and returns an audioUrl that plays INLINE in the chat. This tool cannot return audio bytes directly, so to let the user hear it you MUST copy the exact audioUrl string from this tool's OWN result into your reply, verbatim, on its own line. Do NOT construct, paraphrase, guess, or reuse a URL from a previous turn or from this description - only the literal audioUrl this specific call returns will actually play; any other string (even one that looks plausible) renders as dead text. If you have NOT called this tool during your current turn, you must never write an audioUrl, a path under /api/.../audio/, or any text implying audio was generated ('Here is the audio...', 'Generated speech', etc.) - the frontend renders any such reference as a real audio player, and one with no matching tool call fails to load, which is worse than saying nothing. If a request cannot be fulfilled literally (e.g. 'sing this' - you can speak text aloud but not sing a melody), either call this tool to speak the words as plain TTS and say plainly that's what you did, or explain in plain text why you can't - never fabricate a result to appear compliant. Use it when the user asks for a voice reply, an audio version of some text, or wants to hear something spoken aloud. language is an optional BCP-47 code (e.g. 'hi-IN', 'ta-IN', 'en-IN') honored only by providers that support multiple languages; voice is an optional provider-specific voice/speaker id or name. Both are safe to omit and are ignored by a provider that doesn't use them.
