package com.agenticai.core.voice.dto;

/**
 * OPTIONAL {@code POST /api/voice/agents/{agentId}/token} request body -
 * absent entirely (the pre-existing wire shape, still sent by every caller
 * that predates sight mode) means the same thing as {@code {"mode":"voice"}}.
 *
 * <p>{@code mode} selects which kind of call room the token is minted for:
 * <ul>
 *   <li>{@code "voice"} (or null/blank/absent) - audio-only call, room name
 *       prefixed {@code voice-}, exactly as before this record existed.</li>
 *   <li>{@code "sight"} - live-perception call, room name prefixed
 *       {@code sight-}. Everything else about the token (identity, the
 *       {@code coreAccessToken} metadata claim, TTL, grants - camera publish
 *       was already granted via {@code canPublish}) is byte-identical to a
 *       voice token; the prefix is the ONLY difference, because the room
 *       name is the only channel that reaches the {@code voice-agent}
 *       worker's dispatcher ({@code voice-agent/app/main.py}'s
 *       {@code _ROOM_NAME_RE}), which switches its session into perception
 *       mode (subscribe to the caller's camera track, sample frames, chat
 *       about what it sees) when it parses this prefix.</li>
 * </ul>
 *
 * <p>{@code ttsVoice}/{@code ttsVoiceProvider} are the caller's optional
 * per-call reply-voice pick (the call panel's "call voice" selector): they
 * ride into the room inside the LiveKit token's {@code metadata} claim
 * alongside {@code coreAccessToken} (see {@code
 * LiveKitTokenService#issueToken(String,String,String,Map)}) and are applied
 * by the {@code voice-agent} worker ({@code main.py}'s {@code
 * _extract_tts_voice}) as that call's TTS voice_id/speaker. {@code
 * ttsVoiceProvider} tags which backend {@code ttsVoice} belongs to, since the
 * worker's provider resolution ("auto") is opaque to the browser - a value
 * that doesn't match the resolved provider is ignored, never misapplied.
 * Both are optional and absent for every pre-existing caller.
 */
public record VoiceTokenRequest(String mode, String ttsVoice, String ttsVoiceProvider) {
}
