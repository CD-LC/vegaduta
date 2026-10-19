package com.agenticai.core.voice.dto;

/**
 * {@code POST /api/voice/agents/{agentId}/token} response - everything the
 * frontend's LiveKit client SDK needs to join the call: a short-lived signed
 * token, the LiveKit server's WebSocket URL, and the room/identity it was
 * scoped to (surfaced for debugging/display, not required for the connect
 * call itself since they're already baked into {@code token}).
 */
public record VoiceTokenResponse(String token, String url, String roomName, String identity) {
}
