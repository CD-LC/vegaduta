package com.agenticai.core.voice.dto;

/**
 * {@code transcript} SSE event payload on the streaming {@code POST
 * /api/voice/agents/{agentId}/turn} response - sent exactly once, immediately
 * after speech-to-text completes and before any reply text has been
 * generated, so the caller's own words can appear on screen without waiting
 * for the agent's reply.
 */
public record VoiceTurnTranscriptEvent(String transcript, String method) {
}
