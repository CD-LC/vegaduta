package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * F-17: {@link WhatsAppOutputService} must be safe under at-least-once
 * workflow execution - a retried Output node must not message the recipient
 * twice. Uses a REAL {@link WorkflowOutputDedupGuard} over a mocked Redis so
 * the fail-open guarantee is exercised end to end, same approach as {@link
 * EmailOutputServiceTest}.
 */
class WhatsAppOutputServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private WhatsAppDispatchService dispatchService;
    private WhatsAppOutputService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WorkflowOutputDedupGuard guard = new WorkflowOutputDedupGuard(
            redisTemplate, 10L, mock(GovernanceDegradationSignal.class)
        );
        dispatchService = mock(WhatsAppDispatchService.class);
        when(dispatchService.send(anyString(), anyString())).thenReturn("Sent WhatsApp message via Twilio.");
        ObjectProvider<WhatsAppDispatchService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatchService);
        service = new WhatsAppOutputService(provider, guard);
    }

    @Test
    void firstSendGoesOutAndClaimsTheDedupKey() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        String result = service.send("+15551234589", "hello", "run-1", "node-1");

        assertEquals("Sent WhatsApp message via Twilio.", result);
        verify(dispatchService).send("+15551234589", "hello");
    }

    @Test
    void duplicateSendIsSuppressedWithoutDispatchingAndReturnsTheCallersSuccessShape() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        String result = service.send("+15551234589", "hello", "run-1", "node-1");

        assertTrue(result.contains("Skipped duplicate WhatsApp message"));
        verify(dispatchService, never()).send(anyString(), anyString());
    }

    @Test
    void sendsAnywayWhenRedisIsDown() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("connection refused"));

        service.send("+15551234589", "hello", "run-1", "node-1");

        verify(dispatchService).send("+15551234589", "hello");
    }

    @Test
    void distinctRunsOrNodesDeriveDistinctKeysAndBothSendAndKeysNeverContainTheRawNumber() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        service.send("+15551234589", "message one", "run-1", "node-1");
        service.send("+15551234589", "message one", "run-2", "node-1");

        verify(dispatchService, times(2)).send(anyString(), anyString());
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        // Masking discipline: keyed on (runId, nodeId) now, so the unmasked
        // number never reaches the Redis key at all.
        keys.getAllValues().forEach(key -> assertFalse(key.contains("15551234589")));
    }

    @Test
    void aRetryWithDriftedBodyButTheSameRunAndNodeIsStillSuppressed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenReturn(true, false);

        service.send("+15551234589", "message one", "run-1", "node-1");
        String secondResult = service.send("+15551234589", "message one - drifted", "run-1", "node-1");

        assertTrue(secondResult.contains("Skipped duplicate WhatsApp message"));
        verify(dispatchService, times(1)).send(anyString(), anyString());
    }

    @Test
    void aFailedDispatchReleasesTheClaimSoARetryIsNotSuppressed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(dispatchService.send(anyString(), anyString()))
            .thenThrow(new ChannelDeliveryException("whatsapp", "Twilio 500"));

        assertThrows(ChannelDeliveryException.class, () -> service.send("+15551234589", "hello", "run-1", "node-1"));

        ArgumentCaptor<String> claimedKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(claimedKey.capture(), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(claimedKey.getValue());
    }

    // --- nlflow W5: per-recipient dedup and approved-template sends ---

    @Test
    void aLoopBodyMessagingDifferentRecipientsIsNotSuppressedAsADuplicate() {
        // Same run, same node (one Output node repeated by a loop), two different people.
        java.util.Set<String> claimed = new java.util.HashSet<>();
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenAnswer(inv -> claimed.add(inv.getArgument(0)));

        service.send("+15551230001", "hello", "run-1", "node-1");
        service.send("+15551230002", "hello", "run-1", "node-1");
        String retry = service.send("+15551230001", "hello again", "run-1", "node-1");

        verify(dispatchService).send("+15551230001", "hello");
        verify(dispatchService).send("+15551230002", "hello");
        assertTrue(retry.contains("Skipped duplicate WhatsApp message"), "the same recipient twice is still a retry");
        assertEquals(2, claimed.size());
        claimed.forEach(key -> assertFalse(key.contains("1555123000"), key));
    }

    @Test
    void recipientTagIgnoresFormattingAndNeverContainsTheNumber() {
        assertEquals(WhatsAppOutputService.recipientTag("+1 (555) 123-0001"), WhatsAppOutputService.recipientTag("15551230001"));
        assertNotEquals(WhatsAppOutputService.recipientTag("15551230001"), WhatsAppOutputService.recipientTag("15551230002"));
        assertEquals(12, WhatsAppOutputService.recipientTag("15551230001").length());
    }

    @Test
    void aTemplateSendGoesThroughTheDispatcherUnderTheSameDedupGuard() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true, false);
        when(dispatchService.sendTemplate("+15551234589", "invoice_reminder", "en_US", java.util.List.of("Asha"), "fallback"))
            .thenReturn("Sent template.");

        String first = service.sendTemplate(
            "+15551234589", " invoice_reminder ", "en_US", java.util.List.of("Asha"), "fallback", "run-1", "node-1");
        String second = service.sendTemplate(
            "+15551234589", "invoice_reminder", "en_US", java.util.List.of("Asha"), "fallback", "run-1", "node-1");

        assertEquals("Sent template.", first);
        assertTrue(second.contains("Skipped duplicate WhatsApp message"));
        verify(dispatchService, times(1)).sendTemplate(anyString(), anyString(), anyString(), any(), any());
        verify(dispatchService, never()).send(anyString(), anyString());
    }

    @Test
    void aTemplateSendNeedsARecipientANameAndALanguageAndReleasesItsClaimOnFailure() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        assertThrows(com.agenticai.core.omnichannel.ChannelConfigurationException.class,
            () -> service.sendTemplate(" ", "t", "en", java.util.List.of(), null, "run-1", "node-1"));
        assertThrows(com.agenticai.core.omnichannel.ChannelConfigurationException.class,
            () -> service.sendTemplate("+1555", " ", "en", java.util.List.of(), null, "run-1", "node-1"));
        assertThrows(com.agenticai.core.omnichannel.ChannelConfigurationException.class,
            () -> service.sendTemplate("+1555", "t", null, java.util.List.of(), null, "run-1", "node-1"));
        verify(valueOperations, never()).setIfAbsent(anyString(), eq("1"), any(Duration.class));

        when(dispatchService.sendTemplate(anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new ChannelDeliveryException("whatsapp", "Meta 400"));
        assertThrows(ChannelDeliveryException.class,
            () -> service.sendTemplate("+1555", "t", "en", null, null, "run-1", "node-1"));
        ArgumentCaptor<String> claimedKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(claimedKey.capture(), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(claimedKey.getValue());
    }
}
