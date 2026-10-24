---
title: Tool description: upload_video_to_youtube
description: The description of the upload_video_to_youtube tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Uploads a video previously created with generate_video to the tenant's connected YouTube channel, as a PRIVATE (or unlisted) upload - never public. Tell the user it uploaded privately and that they need to review it and change visibility themselves in YouTube Studio before it's public. Requires the video's job id from a completed generate_video call. Use only when the user explicitly asks to upload/publish/post a generated video to YouTube.
