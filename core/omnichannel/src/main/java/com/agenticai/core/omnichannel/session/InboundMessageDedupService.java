package com.agenticai.core.omnichannel.session;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency guard for inbound channel webhooks - Master-Roadmap-2026.md item #1 (DM/inbox
 * automation quality), gap (b)#3: "no inbound dedup" was a concrete, real reliability bug, not a
 * hardening nice-to-have. Neither {@code TwilioWebhookController} nor {@code
 * TelegramWebhookController} checked the provider's own per-message id before processing - a
 * webhook retry (Twilio retries on anything but a fast 200; Telegram replays pending updates after
 * any webhook downtime) would silently create a second {@link ChannelMessageLog} row, a second
 * agent reply, a second workflow trigger, or a second pairing-code notice for the exact same
 * inbound message.
 *
 * <p>Same shape as {@link com.agenticai.core.omnichannel.trigger.WorkflowTriggerRateLimiter} -
 * plain {@link StringRedisTemplate}, fail-open if Redis is unreachable (a missed dedup on a rare
 * double-delivery is far cheaper than every inbound message on this channel silently going
 * unanswered because of an infra hiccup) - but uses {@code SETNX}-with-TTL ({@link
 * org.springframework.data.redis.core.ValueOperations#setIfAbsent}) instead of {@code INCR}, since
 * this is a "have I seen this exact id before" check, not a rate-limit counter.
 *
 * <p>Fail-open is deliberate but must be OBSERVABLE (Wave-2): every branch that skips the check
 * because Redis couldn't answer now also reports to {@link GovernanceDegradationSignal}, so a Redis
 * outage that silently disables inbound dedup across every channel at once shows up on the same
 * {@code governance_fail_open_total} dashboard/alert as the other fail-open governance seams,
 * instead of only as per-call log noise.
 */
@Component
public class InboundMessageDedupService {

    private static final String KEY_PREFIX = "agentic:inbound-dedup:";
    // Component tag on GovernanceDegradationSignal.METRIC_NAME - one name
    // for every fail-open branch in this class, so the dashboard sees
    // "inbound dedup is degraded" as a single series regardless of which
    // channel's webhook tripped it.
    private static final String DEGRADATION_COMPONENT = "inbound-message-dedup";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final GovernanceDegradationSignal degradationSignal;

    public InboundMessageDedupService(
            StringRedisTemplate redisTemplate,
            @Value("${app.omnichannel.dedup.ttl-minutes:60}") long ttlMinutes,
            GovernanceDegradationSignal degradationSignal) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.degradationSignal = degradationSignal;
    }

    /**
     * @param channel which channel this provider-assigned id belongs to (Twilio {@code MessageSid}
     *     and Telegram {@code update_id} live in entirely separate id spaces, so the key must be
     *     channel-scoped).
     * @param providerMessageId the provider's own unique id for this specific inbound delivery
     *     (Twilio's {@code MessageSid}, Telegram's {@code update_id}, Meta's {@code messages[].id}
     *     wamid). If null/blank (a provider that doesn't supply one, or a call site that hasn't
     *     been wired up yet), this always returns true - dedup is opt-in per call site, never a
     *     reason to drop a message that has no id to dedup on.
     * @return true if this is the first delivery of this id seen within the TTL window (the caller
     *     should process it normally); false if it's a duplicate delivery (the caller should skip
     *     all processing - agent reply, workflow trigger, pairing notice - and just return its
     *     normal fast 200/empty-TwiML ack, exactly as if the message had succeeded the first time,
     *     since from the provider's point of view it did).
     */
    public boolean tryClaim(ChannelType channel, String providerMessageId) {
        return tryClaim(channel.name(), providerMessageId);
    }

    /**
     * String-scoped variant for inbound surfaces that have no {@link ChannelType} of their own -
     * today only inbound SMS ({@code TwilioSmsWebhookController}), which is deliberately a
     * workflow-trigger surface with no chat/pairing/session model (see that class's javadoc), so
     * adding an SMS value to {@link ChannelType} - an enum whose every value feeds {@link
     * ChannelSessionService}'s session machinery - just to name a Redis key scope would be
     * misleading. Twilio MessageSids are unique account-wide (shared between SMS and WhatsApp
     * deliveries), so the distinct "SMS" scope is naming consistency, not collision avoidance. Same
     * contract as the enum variant in every other respect.
     */
    public boolean tryClaim(String channelScope, String providerMessageId) {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            return true;
        }
        String key = key(channelScope, providerMessageId);
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            if (claimed == null) {
                // Not a documented return value for a reachable Redis, but
                // treated as "claim it" defensively rather than as a
                // duplicate, matching this class's fail-open posture - and
                // reported as a degradation, same as
                // LlmGatewayRateLimiterService treats a null INCR result.
                degradationSignal.failOpen(DEGRADATION_COMPONENT, null);
                return true;
            }
            // setIfAbsent returns false only when the key already existed
            // (a genuine duplicate).
            return claimed;
        } catch (RuntimeException e) {
            // Redis unreachable - fail open (see class javadoc), but make
            // the degradation observable: a dedup skipped here is a
            // governance check not enforced, exactly what
            // governance_fail_open_total exists to count.
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return true;
        }
    }

    /**
     * Gives back a claim taken by {@link #tryClaim} so a provider redelivery of the same id is
     * processed instead of swallowed as a duplicate. Call it when processing the claimed message
     * failed or was rejected under load. Must run with the same {@link TenantContext} the claim was
     * taken under (the key is tenant-scoped). Best effort: a Redis failure is ignored (the claim
     * then simply expires at its TTL) and a null/blank id is a no-op.
     */
    public void release(ChannelType channel, String providerMessageId) {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(channel.name(), providerMessageId));
        } catch (RuntimeException e) {
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
        }
    }

    private static String key(String channelScope, String providerMessageId) {
        String tenant =
                TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
        return KEY_PREFIX + tenant + ":" + channelScope + ":" + providerMessageId;
    }
}
