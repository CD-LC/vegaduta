package com.agenticai.core.agents.memorystore;

import com.agenticai.core.agents.memory.spi.MemoryItemView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryRecallResult;
import com.agenticai.core.agents.memory.spi.MemoryRecallSource;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Merges the results of every registered {@link MemoryRecallSource} into one list under the prompt
 * budget (ADR-0028 section 9: 800 tokens, 120 per item, 8 items by default). Nothing here renders
 * text; callers get ids, kinds, text and scores. Sources only return TRUSTED items, so no
 * spotlighting is applied at this layer.
 *
 * <p>With the platform flag off, or a lower level vetoing, no source is called and the result is
 * empty. A source that throws is skipped and reported in {@code degradedReason}; the turn goes on
 * without it.
 */
public class MemoryRecallComposer implements UnifiedMemoryRecall {

    static final String DISABLED = UnifiedMemoryRecall.DISABLED;

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallComposer.class);

    private final MemoryUnifiedProperties properties;
    private final MemoryUnifiedGate gate;
    private final ObjectProvider<MemoryRecallSource> sources;
    private final MemoryRecallProperties relevance;

    public MemoryRecallComposer(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            ObjectProvider<MemoryRecallSource> sources) {
        this(properties, gate, sources, MemoryRecallProperties.off());
    }

    /**
     * {@code relevance}: the similarity floor ({@code app.memory.recall.min-similarity}); null = no
     * floor.
     */
    public MemoryRecallComposer(
            MemoryUnifiedProperties properties,
            MemoryUnifiedGate gate,
            ObjectProvider<MemoryRecallSource> sources,
            MemoryRecallProperties relevance) {
        this.properties = properties;
        this.gate = gate;
        this.sources = sources;
        this.relevance = relevance == null ? MemoryRecallProperties.off() : relevance;
    }

    @Override
    public int totalTokens() {
        return properties.getTotalTokens();
    }

    @Override
    public MemoryRecallResult compose(MemoryRecallRequest request) {
        if (request == null || request.agentId() == null || !gate.enabled(request.agentId())) {
            return MemoryRecallResult.empty(DISABLED);
        }
        Set<MemoryKind> allowed = allowedKinds(request);
        int maxItems = cap(request.maxItems(), properties.getMaxItems());
        int budget = cap(request.tokenBudget(), properties.getTotalTokens());
        if (allowed.isEmpty() || maxItems == 0 || budget == 0) {
            return MemoryRecallResult.empty(null);
        }

        List<MemoryItemView> merged = new ArrayList<>();
        List<String> degraded = new ArrayList<>();
        MemoryRecallRequest forSources =
                new MemoryRecallRequest(
                        request.agentId(),
                        request.subjectKey(),
                        request.queryText(),
                        allowed,
                        maxItems,
                        budget);
        sources.orderedStream()
                .forEach(source -> collect(source, forSources, allowed, merged, degraded));

        merged.sort(
                Comparator.comparingDouble(MemoryItemView::score)
                        .reversed()
                        .thenComparing(v -> v.id().toString()));
        return fit(
                merged, maxItems, budget, degraded.isEmpty() ? null : String.join(",", degraded));
    }

    private Set<MemoryKind> allowedKinds(MemoryRecallRequest request) {
        Set<MemoryKind> platform = EnumSet.of(MemoryKind.SEMANTIC);
        platform.addAll(properties.getKinds());
        Set<MemoryKind> out = EnumSet.noneOf(MemoryKind.class);
        for (MemoryKind kind : platform) {
            if (request.wants(kind)) {
                out.add(kind);
            }
        }
        return out;
    }

    private void collect(
            MemoryRecallSource source,
            MemoryRecallRequest request,
            Set<MemoryKind> allowed,
            List<MemoryItemView> into,
            List<String> degraded) {
        if (source.kinds().stream().noneMatch(allowed::contains)) {
            return;
        }
        try {
            MemoryRecallResult result = source.recall(request);
            if (result == null) {
                return;
            }
            if (result.degradedReason() != null) {
                degraded.add(source.name() + ":" + result.degradedReason());
            }
            for (MemoryItemView item : result.items()) {
                // Defence in depth: a view is TRUSTED by construction, and a kind we did not ask
                // for is dropped.
                if (item.provenance().trust() == MemoryTrustLevel.TRUSTED
                        && allowed.contains(item.kind())) {
                    // The floor is against unrelated lines; the self-model line is derived, not
                    // matched to the message.
                    if (item.kind() == MemoryKind.SELF_MODEL || relevance.passes(item.score())) {
                        into.add(item);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Memory recall source {} failed; skipping it", source.name(), e);
            degraded.add(source.name() + ":error");
        }
    }

    private MemoryRecallResult fit(
            List<MemoryItemView> sorted, int maxItems, int budget, String degradedReason) {
        List<MemoryItemView> kept = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int used = 0;
        int dropped = 0;
        for (MemoryItemView item : sorted) {
            if (!seen.add(item.id())) {
                continue;
            }
            String text = MemoryTokens.truncate(item.text(), properties.getPerItemTokens());
            int tokens = text == null ? 0 : MemoryTokens.estimate(text);
            if (text == null || kept.size() >= maxItems || used + tokens > budget) {
                dropped++;
                continue;
            }
            kept.add(
                    text.equals(item.text())
                            ? item
                            : new MemoryItemView(
                                    item.id(), item.kind(), text, item.score(), item.provenance()));
            used += tokens;
        }
        return new MemoryRecallResult(kept, used, dropped, degradedReason);
    }

    private static int cap(int requested, int platform) {
        return requested > 0 ? Math.min(requested, platform) : platform;
    }
}
