package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ImessageRateLimiterTest {

    @Test
    void inboundIsLimitedPerChatAndRecoversAfterTheWindow() {
        AtomicLong now = new AtomicLong(1_000_000);
        ImessageRateLimiter limiter = new ImessageRateLimiter(3, 3, now::get);

        assertThat(limiter.tryInbound("chat-a")).isTrue();
        assertThat(limiter.tryInbound("chat-a")).isTrue();
        assertThat(limiter.tryInbound("chat-a")).isTrue();
        assertThat(limiter.tryInbound("chat-a")).isFalse();
        assertThat(limiter.tryInbound("chat-b")).as("other chats are unaffected").isTrue();

        now.addAndGet(60_001);
        assertThat(limiter.tryInbound("chat-a")).isTrue();
    }

    @Test
    void outboundIsLimitedPerTenant() {
        ImessageRateLimiter limiter = new ImessageRateLimiter(20, 2, () -> 5L);
        assertThat(limiter.tryOutbound("t1")).isTrue();
        assertThat(limiter.tryOutbound("t1")).isTrue();
        assertThat(limiter.tryOutbound("t1")).isFalse();
        assertThat(limiter.tryOutbound("t2")).isTrue();
    }

    @Test
    void zeroDisablesAWindow() {
        ImessageRateLimiter limiter = new ImessageRateLimiter(0, 0, () -> 1L);
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.tryInbound("c")).isTrue();
            assertThat(limiter.tryOutbound("t")).isTrue();
        }
    }
}
