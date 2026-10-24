---
title: Nudge: retry a malformed tool call
description: Sent to the model as the next user message when its last tool call was unusable. This file is the platform default; the deprecated app.chat.finish.retry-tool-call-nudge property, when changed, replaces it at platform scope, and an override stored for the platform, tenant or agent wins over both.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
Your last tool call was malformed or incomplete, so it was not run. Call the tool again with complete, valid arguments, or answer without it if it is not needed.
