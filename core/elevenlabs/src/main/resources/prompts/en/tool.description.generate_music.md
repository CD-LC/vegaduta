---
title: Tool description: generate_music
description: The description of the generate_music tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Composes an original piece of music from a text description and returns an audioUrl that plays INLINE in the chat. This tool cannot return audio bytes directly, so to let the user hear it you MUST include the returned audioUrl verbatim in your reply (the chat renders any /api/musicgen/tracks/... URL as an audio player). Use it when the user asks for music, a song, a jingle, a soundtrack, a beat, background or hold music. Do NOT use it to read text aloud - that is the speak tool. Describe genre, mood, tempo and instruments in the prompt; do not name real artists or copyrighted songs.
