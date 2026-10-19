package com.agenticai.core.voice.realtime;

/**
 * {@code POST /api/voice/realtime/sessions} reply - everything the browser
 * needs to open the relay socket. The provider API key is deliberately NOT
 * here: it never leaves the server (the relay opens the upstream session
 * itself), which is the whole point of relaying instead of letting the
 * browser dial the provider directly.
 *
 * @param relayPath        WebSocket path to connect to (e.g. {@code
 *                         wss://api.example.com/webhook/voice/realtime});
 *                         path-only, the client supplies its own origin
 * @param ticket           opaque admission ticket for the relay's setup frame
 * @param provider         the resolved provider
 * @param model            the model the upstream session will use
 * @param inputSampleRate  PCM16 sample rate the client must send (16000 for
 *                         Gemini Live, 24000 for OpenAI Realtime)
 * @param outputSampleRate PCM16 rate of audio chunks the relay sends down
 *                         (24000 for both)
 */
public record RealtimeSessionResponse(
    String relayPath,
    String ticket,
    String provider,
    String model,
    int inputSampleRate,
    int outputSampleRate
) {}
