package com.agenticai.core.voice.realtime;

/**
 * Everything a realtime provider needs to open its upstream WebSocket.
 *
 * @param apiKey       the tenant's (or platform's) provider key - resolved
 *                     server-side by {@code ProviderCredentialResolver} and
 *                     NEVER sent to the browser; the browser only ever holds
 *                     the opaque relay ticket
 * @param model        provider model id (e.g. {@code gpt-realtime}, {@code
 *                     gemini-2.5-flash-native-audio-preview-09-2025})
 * @param systemPrompt the agent's system prompt, passed as the provider's
 *                     session instructions so the realtime path speaks with
 *                     the same persona the text/STT pipeline uses
 * @param voice        provider voice id ({@code alloy}-style for OpenAI,
 *                     {@code Puck}-style for Gemini Live), or null for the
 *                     provider default
 */
public record RealtimeSessionConfig(
    String apiKey,
    String model,
    String systemPrompt,
    String voice
) {}
