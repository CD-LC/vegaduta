"""voice-agent: real-time LiveKit voice pipeline for the platform's agents.

See voice-agent/README.md for the full picture. In one line: this package is
a LiveKit Agents Python worker that joins the same self-hosted LiveKit rooms
core/voice's push-to-talk `CallPanel.tsx` already joins, and bridges
VAD-detected utterances through sidecar Whisper (STT) -> core's existing
`/api/agents/{agentId}/chat` streaming pipeline (the "LLM" leg, bridged, not
reimplemented) -> ElevenLabs (TTS), so the "Call" button becomes an actual
hands-free conversation instead of an empty WebRTC room.
"""
