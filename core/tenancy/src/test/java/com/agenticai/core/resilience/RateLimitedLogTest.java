package com.agenticai.core.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** {@link RateLimitedLog#warnf} is the drop-in used at the degraded-path call sites. */
class RateLimitedLogTest {

    @Test
    void warnfLimitsPerCallSiteNotPerArgumentAndReportsWhatWasSuppressed() {
        Logger log = mock(Logger.class);
        long[] now = {0L};
        RateLimitedLog limited = new RateLimitedLog(log, Duration.ofSeconds(60), () -> now[0]);
        RuntimeException redisDown = new RuntimeException("connection refused");

        assertThat(limited.warnf("Rate limiter could not reach Redis for tenant {}", "t1", redisDown)).isTrue();
        // same call site, different tenant: still suppressed - arguments must not multiply the keys
        assertThat(limited.warnf("Rate limiter could not reach Redis for tenant {}", "t2", redisDown)).isFalse();
        assertThat(limited.warnf("Rate limiter could not reach Redis for tenant {}", "t3", redisDown)).isFalse();
        // a different call site is independent
        assertThat(limited.warnf("Cache read failed for agent {}", "a1", redisDown)).isTrue();

        now[0] = 60_000L;
        assertThat(limited.warnf("Rate limiter could not reach Redis for tenant {}", "t4", redisDown)).isTrue();

        verify(log).warn("Rate limiter could not reach Redis for tenant {}", new Object[] {"t1", redisDown});
        verify(log).warn("Cache read failed for agent {}", new Object[] {"a1", redisDown});
        verify(log).warn(
            "Rate limiter could not reach Redis for tenant {} (2 similar event(s) suppressed since the last report)",
            new Object[] {"t4", redisDown});
        verify(log, times(0)).warn("Rate limiter could not reach Redis for tenant {}", new Object[] {"t2", redisDown});
        verifyNoMoreInteractions(log);
    }
}
