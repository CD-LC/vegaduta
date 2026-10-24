---
title: Tool description: generate_video
description: The description of the generate_video tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Starts generating a short video from a text description. Video generation takes tens of seconds to a few minutes and runs in the background - this tool does not wait for it to finish. It returns a job id and URLs; tell the user their video is being generated and share the videoUrl, which starts serving the finished video once ready (returns 404 until then - the user's client is expected to retry it). Use it when the user asks to create, make, generate, or produce a video or short clip from a description.
