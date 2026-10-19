package com.agenticai.core.voice.realtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Gemini Live takes its key in the WebSocket URL; neither a bad key nor a failed handshake may echo it. */
class GeminiLiveKeyLeakTest {

    private static final String KEY = "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY123";

    private static RealtimeSessionConfig config(String key) {
        return new RealtimeSessionConfig(key, "models/gemini-live", "sys", "Puck");
    }

    @Test
    void aFailedHandshakeThatQuotesTheUrlDoesNotLeakTheKey() {
        HttpClient http = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
            .thenReturn(CompletableFuture.failedFuture(
                new java.io.IOException("HTTP 400 for wss://example/ws?key=" + KEY + " Bearer abcdefghijklmnop")));

        RealtimeVoiceException e = assertThrows(RealtimeVoiceException.class,
            () -> new GeminiLiveRealtimeProvider(http).open(config(KEY), mock(RealtimeVoiceListener.class)));

        assertFalse(describe(e).contains(KEY), describe(e));
        assertFalse(describe(e).contains("abcdefghijklmnop"), describe(e));
    }

    @Test
    void aKeyWithAnIllegalCharacterIsEncodedNotQuotedInAnException() {
        HttpClient http = mock(HttpClient.class);
        WebSocket.Builder builder = mock(WebSocket.Builder.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
            .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("down")));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);

        assertThrows(RealtimeVoiceException.class,
            () -> new GeminiLiveRealtimeProvider(http).open(config("bad key\"x"), mock(RealtimeVoiceListener.class)));

        org.mockito.Mockito.verify(builder).buildAsync(uri.capture(), any(WebSocket.Listener.class));
        assertTrue(uri.getValue().getRawQuery().startsWith("key=bad+key%22x"), uri.getValue().getRawQuery());
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }
}
