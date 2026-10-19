package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.telegram.TelegramCredentialService;
import com.agenticai.core.omnichannel.telegram.TelegramTenantBotToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * F-17: {@link TelegramOutputService} must be safe under at-least-once workflow execution - a
 * retried Output node must not message the chat twice. Uses the service's package-private {@link
 * HttpClient} test seam (no live Telegram API) and a REAL {@link WorkflowOutputDedupGuard} over a
 * mocked Redis, so the fail-open guarantee is exercised end to end, same approach as {@link
 * EmailOutputServiceTest}.
 */
class TelegramOutputServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private HttpClient httpClient;
    private TelegramOutputService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WorkflowOutputDedupGuard guard =
                new WorkflowOutputDedupGuard(
                        redisTemplate, 10L, mock(GovernanceDegradationSignal.class));
        TelegramCredentialService credentialService = mock(TelegramCredentialService.class);
        // No per-tenant bot connected - the service falls back to the
        // platform-wide token, the simplest path for these dedup tests.
        when(credentialService.resolveActive()).thenThrow(new RuntimeException("no tenant bot"));
        httpClient = mock(HttpClient.class);
        service =
                new TelegramOutputService(
                        "platform-token",
                        List.of(new TelegramTenantBotToken(credentialService)),
                        new ObjectMapper(),
                        guard,
                        httpClient);
    }

    @SuppressWarnings("unchecked")
    private void telegramRespondsWithStatus(int statusCode) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn("{}");
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    @Test
    void firstSendGoesOutAndClaimsTheDedupKey() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);
        telegramRespondsWithStatus(200);

        String result = service.send("chat-1", "hello", "run-1", "node-1");

        assertEquals("Sent to Telegram chat chat-1.", result);
        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void duplicateSendIsSuppressedWithoutTouchingTelegramAndReturnsTheCallersSuccessShape()
            throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);

        String result = service.send("chat-1", "hello", "run-1", "node-1");

        assertTrue(result.contains("Skipped duplicate Telegram message"));
        verify(httpClient, never()).send(any(HttpRequest.class), any());
    }

    @Test
    void sendsAnywayWhenRedisIsDown() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        telegramRespondsWithStatus(200);

        service.send("chat-1", "hello", "run-1", "node-1");

        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    void distinctRunsOrNodesDeriveDistinctKeysAndBothSend() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);
        telegramRespondsWithStatus(200);

        service.send("chat-1", "message one", "run-1", "node-1");
        service.send("chat-1", "message one", "run-1", "node-2");

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void differentChatsOfTheSameNodeInTheSameRunDeriveDistinctKeys() throws Exception {
        // An Output node used as a loop / fan-out body: same run, same node, a different chat per
        // item.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);
        telegramRespondsWithStatus(200);

        service.send("chat-1", "hello", "run-1", "node-1");
        service.send("chat-2", "hello", "run-1", "node-1");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void aRetryWithDriftedTextButTheSameRunAndNodeIsStillSuppressed() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);
        telegramRespondsWithStatus(200);

        service.send("chat-1", "message one", "run-1", "node-1");
        String secondResult = service.send("chat-1", "message one - drifted", "run-1", "node-1");

        assertTrue(secondResult.contains("Skipped duplicate Telegram message"));
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    void aFailedDeliveryReleasesTheClaimSoARetryIsNotSuppressed() throws Exception {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);
        telegramRespondsWithStatus(500);

        assertThrows(
                ChannelDeliveryException.class,
                () -> service.send("chat-1", "hello", "run-1", "node-1"));

        ArgumentCaptor<String> claimedKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(claimedKey.capture(), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(claimedKey.getValue());
    }
}
