package com.agenticai.core.agents.hosted;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link HostedChainOrdering} decides the order the hosted pool is tried in. These pin the four
 * keys in their documented order: known-dead last (pushed back, never dropped), cheapest first,
 * observed latency next, and the admin's priority as the final tie-break and the ONLY key when
 * {@code order-by-cost} is off.
 */
class HostedChainOrderingTest {

    private static PlatformHostedLlmProvider row(String label, String model, int priority) {
        return new PlatformHostedLlmProvider(label, "https://api.example.com", model, "enc",
            true, priority, null, null);
    }

    private static HostedProviderSelection selection(PlatformHostedLlmProvider p) {
        return new HostedProviderSelection(p, "key");
    }

    private static List<String> labels(List<HostedProviderSelection> ordered) {
        return ordered.stream().map(s -> s.provider().getLabel()).toList();
    }

    @Test
    void withCostOrderingOffPriorityAloneDecides() {
        HostedChainOrdering ordering =
            new HostedChainOrdering(new LlmPricingCatalog(), new HostedProviderHealth(3, 120, 180), false);

        List<HostedProviderSelection> ordered = ordering.order(List.of(
            selection(row("third", "claude-opus-4-8", 30)),
            selection(row("first", "unpriced-model", 10)),
            selection(row("second", "gemini-3.1-flash-lite", 20))));

        assertEquals(List.of("first", "second", "third"), labels(ordered));
    }

    @Test
    void anUnpricedModelSortsAfterPricedOnesAndZeroCostFirst() {
        HostedChainOrdering ordering =
            new HostedChainOrdering(new LlmPricingCatalog(), new HostedProviderHealth(3, 120, 180), true);

        PlatformHostedLlmProvider zeroCost = row("local", "whatever", 50);
        zeroCost.setZeroCost(true);

        List<HostedProviderSelection> ordered = ordering.order(List.of(
            selection(row("unpriced", null, 1)),
            selection(row("priced", "claude-haiku-4-5", 2)),
            selection(zeroCost)));

        // The zero-cost row is $0 (first); the row with no model to price (null cost) sorts after
        // every priced row via nullsLast, whatever its priority.
        assertEquals(List.of("local", "priced", "unpriced"), labels(ordered));
    }

    @Test
    void aTrippedRowGoesToTheBackButIsNeverDropped() {
        HostedProviderHealth health = new HostedProviderHealth(1, 120, 180);
        HostedChainOrdering ordering = new HostedChainOrdering(new LlmPricingCatalog(), health, false);

        PlatformHostedLlmProvider dead = row("dead", "m1", 10);
        health.recordFailure(HostedChainOrdering.healthKey(dead));

        List<HostedProviderSelection> ordered = ordering.order(List.of(
            selection(dead),
            selection(row("alive", "m2", 20))));

        // The tripped row would win on priority but is pushed behind the healthy one - yet it stays
        // in the chain, so a pool whose every row is dead still gets tried.
        assertEquals(List.of("alive", "dead"), labels(ordered));
    }

    @Test
    void theOnlyRowIsTriedEvenWhenTripped() {
        HostedProviderHealth health = new HostedProviderHealth(1, 120, 180);
        HostedChainOrdering ordering = new HostedChainOrdering(new LlmPricingCatalog(), health, false);

        PlatformHostedLlmProvider only = row("only", "m1", 10);
        health.recordFailure(HostedChainOrdering.healthKey(only));

        List<HostedProviderSelection> ordered = ordering.order(List.of(selection(only)));

        assertEquals(1, ordered.size());
        assertEquals("only", ordered.get(0).provider().getLabel());
    }

    @Test
    void observedLatencyBreaksTiesAheadOfPriority() {
        HostedProviderHealth health = new HostedProviderHealth(3, 120, 180);
        HostedChainOrdering ordering = new HostedChainOrdering(new LlmPricingCatalog(), health, true);

        // Both unpriced and same priority: the row with a lower observed latency answers first.
        PlatformHostedLlmProvider slow = row("slow", "unpriced-a", 1);
        PlatformHostedLlmProvider fast = row("fast", "unpriced-b", 1);
        health.recordSuccess(HostedChainOrdering.healthKey(slow), 900);
        health.recordSuccess(HostedChainOrdering.healthKey(fast), 100);

        List<HostedProviderSelection> ordered = ordering.order(List.of(selection(slow), selection(fast)));

        assertEquals(List.of("fast", "slow"), labels(ordered));
    }

    @Test
    void healthKeyIdentifiesARowStably() {
        PlatformHostedLlmProvider p = row("groq-main", "llama-3.3-70b", 1);

        // Unsaved rows (no JPA id) key on label+model - stable within a process.
        assertEquals("row:groq-main:llama-3.3-70b", HostedChainOrdering.healthKey(p));
        assertTrue(HostedChainOrdering.healthKey(p).startsWith("row:"));
    }
}
