package com.agenticai.core.omnichannel.trigger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link WorkflowTriggerRateLimiter} is the fix for a real piece of direct
 * user feedback quoted verbatim in its own javadoc: "if I approve so many
 * whatsapp message, then how do i control? every message might trigger
 * workflow isnt it?" - before this class existed, {@link
 * ChannelWorkflowTriggerService} had no concept of "too often" at all, so
 * one chatty approved sender could turn a single pairing approval into an
 * unbounded stream of workflow runs. Zero coverage before this suite
 * despite backing that abuse-throttle guarantee directly.
 *
 * <p>Same {@link StringRedisTemplate}/{@link ValueOperations} manual-mock
 * convention introduced in {@code InboundMessageDedupServiceTest} (a
 * sibling class the javadoc calls "same shape as"), with one extra
 * collaborator this class also calls directly: {@code
 * redisTemplate.expire(key, window)} on the window's first increment.
 */
class WorkflowTriggerRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private WorkflowTriggerRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        limiter = new WorkflowTriggerRateLimiter(redisTemplate, 20, 10L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tryAcquireSetsTheWindowExpiryOnlyOnTheFirstIncrementOfAWindow() {
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:+15551234567";
        when(valueOperations.increment(key)).thenReturn(1L);

        assertTrue(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "+15551234567"));

        verify(redisTemplate).expire(key, Duration.ofMinutes(10));
    }

    @Test
    void tryAcquireDoesNotReExpireOnEverySubsequentIncrementInTheSameWindow() {
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:+15551234567";
        when(valueOperations.increment(key)).thenReturn(5L);

        assertTrue(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "+15551234567"));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void tryAcquireAllowsExactlyUpToTheConfiguredMaxTriggersPerWindow() {
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender";
        when(valueOperations.increment(key)).thenReturn(20L);

        assertTrue(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender"));
    }

    @Test
    void tryAcquireRejectsOnceTheSenderExceedsMaxTriggersForTheWindow() {
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender";
        when(valueOperations.increment(key)).thenReturn(21L);

        assertFalse(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender"));
    }

    @Test
    void tryAcquireFailsOpenReturningTrueWithoutExpiringWhenRedisReturnsANullCount() {
        // increment() returning null is how a StringRedisTemplate backed by an
        // unreachable Redis (Lettuce configured to return null on failure
        // rather than throw, depending on client config) surfaces here - see
        // the class's own inline comment on this branch.
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender";
        when(valueOperations.increment(key)).thenReturn(null);

        assertTrue(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender"));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void tryAcquireScopesTheKeyByChannelAndSenderIndependently() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender-a");
        limiter.tryAcquire(WorkflowTriggerChannel.SMS, "sender-a");
        limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender-b");

        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender-a");
        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:SMS:sender-a");
        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender-b");
    }

    @Test
    void tryAcquireScopesTheKeyByTheCurrentTenantWhenOneIsPresent() {
        TenantContext.set(TenantContext.of("tenant-9", "user", Set.of()));
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender-a");

        verify(valueOperations).increment("agentic:workflow-trigger-rate:tenant-9:WHATSAPP:sender-a");
    }

    @Test
    void tryAcquireFailsOpenReturningTrueWithoutExpiringWhenIncrementThrowsARuntimeException() {
        // Companion to the null-count fail-open test above: this covers the
        // *other* way Redis can be "unreachable" - increment() throwing
        // outright (the actual shape of a real connection failure under
        // Lettuce/Jedis) rather than returning null. This method used to
        // assertThrows and documented that as current (arguably buggy)
        // behavior; tryAcquire() now wraps the increment() call in a
        // try/catch RuntimeException and fails open, matching
        // InboundMessageDedupService.tryClaim's identical pattern - see
        // both classes' javadoc.
        String key = "agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:sender";
        when(valueOperations.increment(key)).thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "sender"));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    // --- new #422 channels (DISCORD/EMAIL) - this limiter is channel-generic,
    // so these confirm DiscordWebhookController.tryAcquireTriggerSlot(DISCORD,
    // channelId) and EmailInboundPollingJob.tryAcquireTriggerSlot(EMAIL,
    // fromAddress) key/scope exactly like WHATSAPP/SMS already do above, not
    // new behavior of the limiter itself. -----------------------------------

    @Test
    void tryAcquireScopesDiscordAndEmailIntoIndependentWindowsFromEachOtherAndFromWhatsapp() {
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.tryAcquire(WorkflowTriggerChannel.DISCORD, "channel-123");
        limiter.tryAcquire(WorkflowTriggerChannel.EMAIL, "someone@example.com");
        limiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "channel-123");

        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:DISCORD:channel-123");
        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:EMAIL:someone@example.com");
        verify(valueOperations).increment("agentic:workflow-trigger-rate:__no-tenant__:WHATSAPP:channel-123");
    }

    @Test
    void tryAcquireRejectsDiscordOnceTheChannelExceedsMaxTriggersForTheWindow() {
        String key = "agentic:workflow-trigger-rate:__no-tenant__:DISCORD:channel-123";
        when(valueOperations.increment(key)).thenReturn(21L);

        assertFalse(limiter.tryAcquire(WorkflowTriggerChannel.DISCORD, "channel-123"));
    }
}
