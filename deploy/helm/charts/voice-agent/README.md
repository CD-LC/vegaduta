# voice-agent

VegaDuta voice agent (`services/voice-agent`, LiveKit Agents worker). Optional (`global.components.voiceAgent`).
Bring your own LiveKit server; set `global.livekit.url` (public `wss://` URL handed to browsers by core),
`global.livekit.internalUrl` (what this worker dials) and `global.livekit.existingSecret`
(`LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`). No Service; probes use TCP on 8081.
