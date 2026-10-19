package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link InboundMessageDedupService} closes a real, previously-shipped reliability gap called out
 * in its own javadoc: neither the Twilio nor Telegram webhook controllers checked the provider's
 * own per-message id before processing, so a webhook retry (both providers retry/replay on anything
 * less than a clean fast ack) could silently double-process one inbound message - a second agent
 * reply, a second workflow trigger, a second pairing notice. Zero coverage before this suite
 * despite backing that exact idempotency guarantee.
 *
 * <p>Mocks {@link StringRedisTemplate} and its {@link ValueOperations} the same manual {@code
 * mock(X.class)} + constructor-injection way every other suite in this codebase does - no
 * {@code @ExtendWith(MockitoExtension.class)}, no embedded/Testcontainers Redis. This is the first
 * Redis-backed class in this codebase to get coverage, so there's no prior mocking convention for
 * {@code StringRedisTemplate} specifically to follow; mocking {@code opsForValue()}'s returned
 * {@link ValueOperations} is the natural equivalent of this codebase's existing "mock the
 * collaborator, verify the real class's own logic" approach.
 */
class InboundMessageDedupServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private GovernanceDegradationSignal degradationSignal;
    private InboundMessageDedupService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        degradationSignal = mock(GovernanceDegradationSignal.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new InboundMessageDedupService(redisTemplate, 60L, degradationSignal);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void tryClaimAlwaysReturnsTrueForANullProviderMessageIdWithoutTouchingRedis() {
        assertTrue(service.tryClaim(ChannelType.WHATSAPP, null));

        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void tryClaimAlwaysReturnsTrueForABlankProviderMessageIdWithoutTouchingRedis() {
        assertTrue(service.tryClaim(ChannelType.WHATSAPP, "   "));

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void tryClaimReturnsTrueAndClaimsTheKeyForAFirstTimeMessageId() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofMinutes(60))))
                .thenReturn(true);

        assertTrue(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));

        verify(valueOperations)
                .setIfAbsent(
                        "agentic:inbound-dedup:__no-tenant__:WHATSAPP:MSG-123",
                        "1",
                        Duration.ofMinutes(60));
    }

    @Test
    void tryClaimReturnsFalseForAKnownDuplicateMessageId() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(false);

        assertFalse(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));
    }

    @Test
    void tryClaimTreatsANullSetIfAbsentResultAsAClaimRatherThanADuplicate() {
        // Not a documented Redis return value for a reachable server, but the
        // class defensively claims it anyway rather than dropping the message -
        // see the class's own inline comment on this exact branch. Wave-2:
        // the branch is also reported as a degradation (with no cause, since
        // there is no exception), same as LlmGatewayRateLimiterService's
        // null-INCR handling.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(null);

        assertTrue(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));

        verify(degradationSignal).failOpen("inbound-message-dedup", null);
    }

    @Test
    void tryClaimFailsOpenReturningTrueWhenRedisIsUnreachable() {
        org.springframework.data.redis.RedisConnectionFailureException redisDown =
                new org.springframework.data.redis.RedisConnectionFailureException(
                        "connection refused");
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenThrow(redisDown);

        assertTrue(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));

        // Wave-2: the fail-open must be observable - a Redis outage silently
        // disabling inbound dedup across every channel is exactly what
        // governance_fail_open_total exists to surface.
        verify(degradationSignal).failOpen("inbound-message-dedup", redisDown);
    }

    @Test
    void tryClaimDoesNotReportDegradationForANormalClaimOrDuplicate() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true, false);

        assertTrue(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));
        assertFalse(service.tryClaim(ChannelType.WHATSAPP, "MSG-123"));

        verify(degradationSignal, never()).failOpen(anyString(), any());
    }

    @Test
    void tryClaimScopesTheKeyByChannelSoTheSameIdOnTwoChannelsAreIndependent() {
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        service.tryClaim(ChannelType.WHATSAPP, "shared-id");
        service.tryClaim(ChannelType.TELEGRAM, "shared-id");

        verify(valueOperations)
                .setIfAbsent(
                        "agentic:inbound-dedup:__no-tenant__:WHATSAPP:shared-id",
                        "1",
                        Duration.ofMinutes(60));
        verify(valueOperations)
                .setIfAbsent(
                        "agentic:inbound-dedup:__no-tenant__:TELEGRAM:shared-id",
                        "1",
                        Duration.ofMinutes(60));
    }

    @Test
    void tryClaimScopesTheKeyByTheCurrentTenantWhenOneIsPresent() {
        TenantContext.set(TenantContext.of("tenant-42", "user-1", Set.of()));
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        service.tryClaim(ChannelType.WHATSAPP, "MSG-1");

        verify(valueOperations)
                .setIfAbsent(
                        "agentic:inbound-dedup:tenant-42:WHATSAPP:MSG-1",
                        "1",
                        Duration.ofMinutes(60));
    }

    @Test
    void tryClaimStringOverloadScopesTheKeyByTheLiteralScopeForSurfacesWithNoChannelType() {
        // Inbound SMS (TwilioSmsWebhookController) has no ChannelType - it's
        // a workflow-trigger surface with no chat/session model - so its
        // call site scopes by the literal "SMS" via the String overload; see
        // that overload's javadoc.
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        assertTrue(service.tryClaim("SMS", "SM-123"));

        verify(valueOperations)
                .setIfAbsent(
                        "agentic:inbound-dedup:__no-tenant__:SMS:SM-123",
                        "1",
                        Duration.ofMinutes(60));
    }

    @Test
    void constructorHonorsTheConfiguredTtlMinutesValue() {
        InboundMessageDedupService thirtyMinuteService =
                new InboundMessageDedupService(redisTemplate, 30L, degradationSignal);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class)))
                .thenReturn(true);

        thirtyMinuteService.tryClaim(ChannelType.WHATSAPP, "MSG-1");

        verify(valueOperations).setIfAbsent(anyString(), eq("1"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void releaseDeletesTheTenantScopedClaimKey() {
        TenantContext.set(TenantContext.of("tenant_a", "sub", Set.of()));

        service.release(ChannelType.TELEGRAM, "42");

        verify(redisTemplate).delete("agentic:inbound-dedup:tenant_a:TELEGRAM:42");
    }

    @Test
    void releaseIsNoOpForBlankIdAndSwallowsRedisFailure() {
        service.release(ChannelType.TELEGRAM, null);
        verify(redisTemplate, never()).delete(anyString());

        when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        service.release(ChannelType.TELEGRAM, "42");
        verify(degradationSignal).failOpen(eq("inbound-message-dedup"), any());
    }
}
