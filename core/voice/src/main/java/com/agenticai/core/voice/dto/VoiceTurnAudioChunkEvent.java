package com.agenticai.core.voice.dto;

/**
 * {@code audio-chunk} SSE event payload on the streaming {@code POST
 * /api/voice/agents/{agentId}/turn} response - one per sentence-sized piece
 * of the reply, emitted in playback order ({@code seq} is 0-based and
 * monotonic - {@link reactor.core.publisher.Flux#flatMapSequential} is what
 * guarantees the wire order matches this even though the underlying TTS
 * calls run concurrently). {@code textFragment} is already speech-normalized
 * (markdown/HTML/emoji stripped, see {@code SpeechStreamNormalizer}), ready
 * to hand to a {@code SpeechSynthesisUtterance} directly. {@code
 * audioBase64} is raw audio bytes (MP3 for ElevenLabs, WAV for Sarvam),
 * base64-encoded, WITHOUT a {@code data:} prefix - same convention {@link
 * VoiceTurnResponse} already uses. Empty when {@code method} is {@code
 * "text-only-fallback"}, in which case the frontend should speak {@code
 * textFragment} itself via the browser's Web Speech API instead of playing
 * audio.
 */
public record VoiceTurnAudioChunkEvent(long seq, String textFragment, String audioBase64, String method) {
}
