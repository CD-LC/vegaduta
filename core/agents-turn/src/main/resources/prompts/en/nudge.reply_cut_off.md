---
title: Nudge: continue a cut-off reply
description: Sent to the model as the next user message when its reply hit the output length limit. This file is the platform default; the deprecated app.chat.finish.continue-nudge property, when changed, replaces it at platform scope, and an override stored for the platform, tenant or agent wins over both.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
Your previous reply was cut off because it reached the output length limit. Continue exactly where it stopped, without repeating anything you already wrote and without any introduction.
