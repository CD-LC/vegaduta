---
title: Tool description: youtube_content_performance
description: The description of the youtube_content_performance tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads public performance stats for the connected YouTube channel. Actions: 'channel_summary' (lifetime subscriber/view/video counts), 'recent_videos' (optional max_results, default 10, max 25 - title, publish date, view/like/comment counts for the most recent uploads, in upload-recency order). Does not include watch-time, traffic sources, or audience retention - view/like/comment counts only.
