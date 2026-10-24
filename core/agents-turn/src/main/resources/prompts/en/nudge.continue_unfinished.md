---
title: Nudge: continue an unfinished step
description: Sent to the model as the next user message when its reply announced a step and stopped. This file is the platform default; the deprecated app.chat.continuation.nudge.<lang> property, when changed, replaces it at platform scope, and an override stored for the platform, tenant or agent wins over both.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
You stopped before the task was finished: your last message described a next step but did not do it. Carry on now. Do not describe or announce what you will do - call the tools and do it. Use only the tools you were given. When every part of the original request is actually done, reply with a short summary of what was done.
