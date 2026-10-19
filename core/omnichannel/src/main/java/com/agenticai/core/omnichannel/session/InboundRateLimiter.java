package com.agenticai.core.omnichannel.session;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.RedisFixedWindowCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * One turn-rate gate for every inbound channel (WhatsApp, Telegram, Slack, Discord, Teams, Meta
 * social, X, email, SMS), applied where they all converge: {@code
 * ChannelSessionService#resolveOrCreate}.
 *
 * <p>Before this, only some channels limited anything, each in its own way, so one chatty (or
 * hostile) sender on an open channel could drive an unbounded number of paid LLM turns. Two
 * fixed-window counters now apply per tenant and channel:
 *
 * <ul>
 *   <li><b>per sender</b> - {@code app.omnichannel.rate-limit.per-sender-per-minute} (default 20);
 *   <li><b>per channel</b> - {@code app.omnichannel.rate-limit.per-channel-per-minute}, all
 *       senders of one tenant on one channel together (default 600).
 * </ul>
 *
 * <p><b>Opt-in.</b> The gate is OFF unless {@code app.omnichannel.rate-limit.enabled=true} (the
 * {@code personal} profile turns it on). It is off by default because no existing channel adapter
 * answers a refused message: Telegram's webhook and the Meta WhatsApp loop swallow the exception
 * after their dedup claim, polling backs off, and on Slack, Discord, Teams and X the "sender" is
 * the room, so a busy shared room would share one budget. Turning it on for an install that
 * already runs those channels is a deliberate choice. When on, the defaults are generous: a person
 * does not send 20 messages a minute, and a 600/min channel is a serious deployment. A value of 0
 * disables just that one counter. VOICE is exempt: a call's utterances are paced by speech, and
 * refusing one mid-call ends it.
 *
 * <p>Counters live in Redis ({@link RedisFixedWindowCounter}, one atomic Lua call) and fall back
 * to an in-process counter when Redis is down - they never fall back to "allowed". The sender is
 * hashed in the key, so no phone number or email address is written to Redis or held in the
 * fallback table.
 *
 * <p>Token and dollar spend are a separate concern with their own gates (the per-user budget and
 * the free-tier limiter in the turn path); this one bounds how MANY turns can be started.
 */
@Component
public class InboundRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InboundRateLimiter.class);
    private static final String KEY_PREFIX = "agentic:inbound-rate:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ObjectProvider<StringRedisTemplate> redis;
    private final boolean enabled;
    private final long perSenderPerMinute;
    private final long perChannelPerMinute;

    public InboundRateLimiter(
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.omnichannel.rate-limit.enabled:false}") boolean enabled,
            @Value("${app.omnichannel.rate-limit.per-sender-per-minute:20}") long perSenderPerMinute,
            @Value("${app.omnichannel.rate-limit.per-channel-per-minute:600}") long perChannelPerMinute) {
        this.redis = redis;
        this.enabled = enabled;
        this.perSenderPerMinute = perSenderPerMinute;
        this.perChannelPerMinute = perChannelPerMinute;
    }

    /**
     * Counts one inbound message from {@code senderId} on {@code channel} and throws {@link
     * InboundRateLimitedException} when it is over a cap. The channel counter is only charged for a
     * message the sender's own counter let through, so a flooding sender cannot use up the room of
     * everyone else on the channel.
     */
    public void checkOrThrow(ChannelType channel, String senderId) {
        if (!enabled || channel == ChannelType.VOICE || senderId == null || senderId.isBlank()) {
            return;
        }
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
        StringRedisTemplate template = redis.getIfAvailable();
        if (perSenderPerMinute > 0) {
            RedisFixedWindowCounter.Usage usage =
                    RedisFixedWindowCounter.acquire(
                            template,
                            KEY_PREFIX + "s:" + tenant + ":" + channel.name() + ":" + fingerprint(senderId),
                            perSenderPerMinute,
                            WINDOW);
            if (!usage.allowed()) {
                log.info("Inbound {} sender over {} messages/min on tenant {}", channel, perSenderPerMinute, tenant);
                throw new InboundRateLimitedException(usage.resetSeconds(), false);
            }
        }
        if (perChannelPerMinute > 0) {
            RedisFixedWindowCounter.Usage usage =
                    RedisFixedWindowCounter.acquire(
                            template,
                            KEY_PREFIX + "c:" + tenant + ":" + channel.name(),
                            perChannelPerMinute,
                            WINDOW);
            if (!usage.allowed()) {
                log.warn("Inbound {} channel over {} messages/min on tenant {}", channel, perChannelPerMinute, tenant);
                throw new InboundRateLimitedException(usage.resetSeconds(), true);
            }
        }
    }

    /** First 16 hex chars of SHA-256: enough to keep senders apart, nothing a person could be read from. */
    static String fingerprint(String senderId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(senderId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
