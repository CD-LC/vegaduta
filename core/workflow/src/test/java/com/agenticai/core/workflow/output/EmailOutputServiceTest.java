package com.agenticai.core.workflow.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * F-17: {@link EmailOutputService} must be safe under at-least-once workflow
 * execution - a retried Output node must not email the recipient twice. Uses
 * a REAL {@link WorkflowOutputDedupGuard} over a mocked Redis (rather than
 * mocking the guard) so the "Redis down => send anyway" fail-open guarantee
 * is exercised end to end through the service, not just assumed of a stub.
 */
class EmailOutputServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private JavaMailSender mailSender;
    private EmailOutputService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        WorkflowOutputDedupGuard guard = new WorkflowOutputDedupGuard(
            redisTemplate, 10L, mock(GovernanceDegradationSignal.class)
        );
        mailSender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> mailSenderProvider = mock(ObjectProvider.class);
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
        service = new EmailOutputService(mailSenderProvider, "smtp.example.com", "workflows@example.com", guard);
    }

    @Test
    void firstSendGoesOutAndClaimsTheDedupKey() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        String result = service.send("a@example.com", "Workflow output: Report", "the body", "run-1", "node-1");

        assertEquals("Emailed output to a@example.com via smtp.example.com.", result);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void duplicateSendIsSuppressedWithoutTouchingSmtpAndReturnsTheCallersSuccessShape() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        String result = service.send("a@example.com", "Workflow output: Report", "the body", "run-1", "node-1");

        // Same success shape (a human-readable trace line, no exception) so
        // WorkflowNodeExecutor records the node as ok, matching the first run.
        assertTrue(result.contains("Skipped duplicate email"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendsAnywayWhenRedisIsDown() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenThrow(new RedisConnectionFailureException("connection refused"));

        service.send("a@example.com", "Workflow output: Report", "the body", "run-1", "node-1");

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void distinctRunsOrNodesDeriveDistinctKeysAndBothSend() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        service.send("a@example.com", "Workflow output: Report", "body one", "run-1", "node-1");
        service.send("a@example.com", "Workflow output: Report", "body one", "run-2", "node-1");

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void aRetryWithDriftedPayloadButTheSameRunAndNodeIsStillSuppressed() {
        // The reason the key moved off content: a retry whose body legitimately
        // differs (e.g. a timestamp baked into the agent's output) must still
        // be recognized as the same node execution, not treated as a new send.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
            .thenReturn(true, false);

        service.send("a@example.com", "Workflow output: Report", "body one", "run-1", "node-1");
        String secondResult = service.send("a@example.com", "Workflow output: Report", "body two - drifted", "run-1", "node-1");

        assertTrue(secondResult.contains("Skipped duplicate email"));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(2)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
    }

    @Test
    void aFailedSmtpHandOffReleasesTheClaimSoARetryIsNotSuppressed() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        doThrow(new MailSendException("smtp refused")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThrows(MailSendException.class,
            () -> service.send("a@example.com", "Workflow output: Report", "the body", "run-1", "node-1"));

        ArgumentCaptor<String> claimedKey = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(claimedKey.capture(), eq("1"), any(Duration.class));
        verify(redisTemplate).delete(claimedKey.getValue());
    }

    @Test
    void differentRecipientsOfTheSameNodeInTheSameRunDeriveDistinctKeysAndNeverLeakTheAddress() {
        // An Output node used as a loop / fan-out body: same run, same node, a different recipient per item.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);

        service.send("a@example.com", "Workflow output: Report", "body", "run-1", "node-1");
        service.send("b@example.com", "Workflow output: Report", "body", "run-1", "node-1");
        service.send(" A@Example.com ", "Workflow output: Report", "body", "run-1", "node-1");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(3)).setIfAbsent(keys.capture(), eq("1"), any(Duration.class));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        // Same recipient (case / whitespace aside) is still the same send.
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
        keys.getAllValues().forEach(key -> assertTrue(!key.contains("example.com"), key));
    }
}
