package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TurnUsageCounterTest {

    private static final Instant T0 = Instant.parse("2031-01-01T00:00:00Z");

    @AfterEach
    void uninstall() {
        TurnUsageCounter.install(null);
    }

    @Test
    void offByDefault() {
        assertNull(TurnUsageCounter.active());
    }

    @Test
    void addsUpTheCallsOfOneSessionAndDrainsThem() {
        TurnUsageCounter c = new TurnUsageCounter();
        c.record("s1", 100L, 20L, 120L, true, 500, T0);
        c.record("s1", 50L, null, null, false, 300, T0.plusMillis(600));
        c.record("s2", 7L, 3L, 10L, true, 10, T0);

        TurnUsageCounter.Totals t = c.peek("s1");
        assertEquals(2, t.calls());
        assertEquals(1, t.failedCalls());
        assertEquals(150, t.promptTokens());
        assertEquals(20, t.completionTokens());
        assertEquals(170, t.totalTokens());
        assertEquals(800, t.modelLatencyMs());
        assertEquals(500, t.maxCallLatencyMs());
        assertEquals(900, t.wallMs());

        assertEquals(t, c.drain("s1"));
        assertEquals(0, c.peek("s1").calls());
        assertEquals(1, c.peek("s2").calls());
    }

    @Test
    void aCallWithNoUsageIsCountedAsSuch() {
        TurnUsageCounter c = new TurnUsageCounter();
        c.record("s", null, null, null, true, 5, T0);
        assertEquals(1, c.peek("s").callsWithoutUsage());
        assertEquals(0, c.peek("s").totalTokens());
    }

    @Test
    void aCallWithoutASessionIsNotTallied() {
        TurnUsageCounter c = new TurnUsageCounter();
        c.record(null, 1L, 1L, 2L, true, 5, T0);
        assertEquals(0, c.sessions());
    }

    @Test
    void theSinkFeedsTheInstalledCounterOnly() {
        TurnUsageSink sink = new TurnUsageSink();
        LlmCallObservation obs =
                new LlmCallObservation(
                        null,
                        "m",
                        new LlmCallObservation.Attribution(null, "sess", null, null, null),
                        new LlmCallObservation.Tokens(
                                10L, 5L, 15L, CacheTokenAccounting.ABSENT, null, null),
                        null,
                        true,
                        false,
                        "r",
                        40,
                        T0,
                        null);
        sink.onCall(obs); // no counter installed: nothing happens
        TurnUsageCounter c = new TurnUsageCounter();
        TurnUsageCounter.install(c);
        sink.onCall(obs);
        assertEquals(15, c.peek("sess").totalTokens());
        assertEquals(40, c.peek("sess").modelLatencyMs());
    }
}
