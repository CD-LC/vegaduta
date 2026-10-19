package com.agenticai.core.voice.dto;

/**
 * The {@code done} SSE event payload on {@code POST
 * /api/voice/agents/{agentId}/turn}'s streaming response - the terminal
 * summary sent once the reply has fully streamed and every sentence's audio
 * has been queued for synthesis (transcript/reply-delta/audio-chunk events
 * carry the incremental pieces; this carries the complete picture for a
 * caller that only cares about the final result). {@code audioBase64} is
 * always empty here - the actual audio already went out as {@code
 * audio-chunk} events - kept as a field only so this record's shape stays
 * stable for anything still parsing it.
 *
 * <p>{@code replyText} is the agent's reply EXACTLY as the model produced it
 * - markdown, emoji and all - because that is what the transcript renders on
 * screen. {@code speechText} is the same reply put through {@code
 * SpeechTextNormalizer} and is what should be SPOKEN: it exists for the
 * {@code "text-only-fallback"} case, where {@code audioBase64} is empty and
 * the frontend synthesizes the reply itself with the browser's Web Speech
 * API. Speaking {@code replyText} there reads the markdown out loud
 * ("asterisk asterisk bold asterisk asterisk") - the 2026-07-29 live bug -
 * so the frontend must use {@code speechText} for any {@code
 * SpeechSynthesisUtterance} and keep {@code replyText} for display.
 */
public record VoiceTurnResponse(String sessionId, String transcript, String replyText, String speechText, String audioBase64) {
}
