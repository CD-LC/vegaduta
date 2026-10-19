package com.agenticai.core.voice.realtime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code POST /api/voice/realtime/sessions} body.
 *
 * @param agentId  which agent's system prompt the session speaks with
 * @param provider {@code "gemini-live"} or {@code "openai-realtime"} (see
 *                 {@link RealtimeProvider#parse})
 * @param model    optional provider model override
 * @param voice    optional provider voice override
 */
public record RealtimeSessionRequest(
    @NotNull UUID agentId,
    @NotNull @Size(max = 32) String provider,
    @Size(max = 128) String model,
    @Size(max = 64) String voice
) {}
