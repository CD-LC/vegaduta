package com.agenticai.core.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** SE-74: registry search answers are reused for a short time, failures are not. */
class SkillRegistryClientSearchCacheTest {

    private static SkillRegistryClient.SearchResult result() {
        return new SkillRegistryClient.SearchResult(List.of(), 1, 1, 0);
    }

    @Test
    void secondIdenticalSearchIsServedFromCacheUntilTtlElapses() {
        SkillRegistryClient client = new SkillRegistryClient(new ObjectMapper(), "", "");
        AtomicLong clock = new AtomicLong(1_000);
        client.setNanoClockForTest(clock::get);
        AtomicInteger loads = new AtomicInteger();

        client.cachedSearch("pdf", 1, () -> { loads.incrementAndGet(); return result(); });
        client.cachedSearch("PDF", 1, () -> { loads.incrementAndGet(); return result(); });
        assertEquals(1, loads.get());

        client.cachedSearch("pdf", 2, () -> { loads.incrementAndGet(); return result(); });
        assertEquals(2, loads.get(), "a different page is a different entry");

        clock.addAndGet(SkillRegistryClient.SEARCH_CACHE_TTL_NANOS + 1);
        client.cachedSearch("pdf", 1, () -> { loads.incrementAndGet(); return result(); });
        assertEquals(3, loads.get());
    }

    @Test
    void failuresAreNotCached() {
        SkillRegistryClient client = new SkillRegistryClient(new ObjectMapper(), "", "");
        assertThrows(IllegalStateException.class, () -> client.cachedSearch("x", 1, () -> {
            throw new IllegalStateException("down");
        }));
        AtomicInteger loads = new AtomicInteger();
        client.cachedSearch("x", 1, () -> { loads.incrementAndGet(); return result(); });
        assertEquals(1, loads.get());
    }
}
