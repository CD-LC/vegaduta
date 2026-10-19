package com.agenticai.core.omnichannel.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** No Redis bean: the limiter must still limit, through the in-process fallback. */
class InboundRateLimiterTest {

    @SuppressWarnings("unchecked")
    private static InboundRateLimiter limiter(boolean enabled, long perSender, long perChannel) {
        ObjectProvider<StringRedisTemplate> none = mock(ObjectProvider.class);
        return new InboundRateLimiter(none, enabled, perSender, perChannel);
    }

    @BeforeEach
    void freshCounters() {
        LocalRateLimitFallback.reset();
    }

    @Test
    void aSenderOverTheirCapIsRefusedWithTheFriendlyMessage() {
        InboundRateLimiter limiter = limiter(true, 3, 1000);

        for (int i = 0; i < 3; i++) {
            limiter.checkOrThrow(ChannelType.TELEGRAM, "chat-1");
        }

        assertThatThrownBy(() -> limiter.checkOrThrow(ChannelType.TELEGRAM, "chat-1"))
                .isInstanceOf(InboundRateLimitedException.class)
                .hasMessage(InboundRateLimitedException.FRIENDLY_MESSAGE)
                .satisfies(
                        e -> {
                            InboundRateLimitedException ex = (InboundRateLimitedException) e;
                            assertThat(ex.isChannelWide()).isFalse();
                            assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 60L);
                        });
    }

    @Test
    void oneSendersFloodDoesNotConsumeAnotherSendersRoom() {
        InboundRateLimiter limiter = limiter(true, 2, 1000);
        limiter.checkOrThrow(ChannelType.WHATSAPP, "+911111111111");
        limiter.checkOrThrow(ChannelType.WHATSAPP, "+911111111111");

        assertThatThrownBy(() -> limiter.checkOrThrow(ChannelType.WHATSAPP, "+911111111111"))
                .isInstanceOf(InboundRateLimitedException.class);
        assertThatCode(() -> limiter.checkOrThrow(ChannelType.WHATSAPP, "+922222222222"))
                .doesNotThrowAnyException();
    }

    @Test
    void theChannelCapBoundsAllSendersTogether() {
        InboundRateLimiter limiter = limiter(true, 100, 3);
        limiter.checkOrThrow(ChannelType.SLACK, "a");
        limiter.checkOrThrow(ChannelType.SLACK, "b");
        limiter.checkOrThrow(ChannelType.SLACK, "c");

        assertThatThrownBy(() -> limiter.checkOrThrow(ChannelType.SLACK, "d"))
                .isInstanceOf(InboundRateLimitedException.class)
                .satisfies(e -> assertThat(((InboundRateLimitedException) e).isChannelWide()).isTrue());
        // A different channel of the same tenant has its own room.
        assertThatCode(() -> limiter.checkOrThrow(ChannelType.DISCORD, "d")).doesNotThrowAnyException();
    }

    @Test
    void disabledZeroCapsAndVoiceNeverLimit() {
        InboundRateLimiter off = limiter(false, 1, 1);
        InboundRateLimiter uncapped = limiter(true, 0, 0);
        InboundRateLimiter strict = limiter(true, 1, 1);

        for (int i = 0; i < 50; i++) {
            off.checkOrThrow(ChannelType.EMAIL, "x@example.com");
            uncapped.checkOrThrow(ChannelType.EMAIL, "x@example.com");
            strict.checkOrThrow(ChannelType.VOICE, "+911111111111");
        }
    }

    @Test
    void blankSendersAreNotCountedAndTheFingerprintHidesTheIdentity() {
        InboundRateLimiter limiter = limiter(true, 1, 1);
        limiter.checkOrThrow(ChannelType.SMS, null);
        limiter.checkOrThrow(ChannelType.SMS, " ");
        limiter.checkOrThrow(ChannelType.SMS, "");

        String fingerprint = InboundRateLimiter.fingerprint("+911111111111");
        assertThat(fingerprint).hasSize(16).doesNotContain("9111");
    }

    @Test
    void theRefusalCarriesNoStackTraceSoAFloodCannotFloodTheLog() {
        InboundRateLimitedException e = new InboundRateLimitedException(30, false);

        assertThat(e.getStackTrace()).isEmpty();
        assertThat(e.getMessage()).isEqualTo(InboundRateLimitedException.FRIENDLY_MESSAGE);
        assertThat(e.getRetryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void theGateIsOptInSoExistingChannelsKeepTheirBehaviour() throws Exception {
        var ctor = InboundRateLimiter.class.getConstructors()[0];
        String enabledExpr =
                ctor.getParameters()[1]
                        .getAnnotation(org.springframework.beans.factory.annotation.Value.class)
                        .value();

        assertThat(enabledExpr).isEqualTo("${app.omnichannel.rate-limit.enabled:false}");
    }
}
