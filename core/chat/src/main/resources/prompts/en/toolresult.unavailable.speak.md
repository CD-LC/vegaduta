---
title: Unavailable tool: speech
description: Tool result when the model calls speak but voice is not switched on for the workspace.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
---
Voice/text-to-speech is not switched on for this workspace, so this tool cannot run. It needs either an ElevenLabs API key on the Providers page plus at least one cloned voice, or a Sarvam credential attached to this agent. Tell the user that and do not call this tool again in this conversation.
