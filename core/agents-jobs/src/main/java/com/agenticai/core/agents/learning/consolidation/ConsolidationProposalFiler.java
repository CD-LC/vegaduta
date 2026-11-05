package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Files the MERGE and SUMMARISE operations of a plan over {@code memory_item} rows as proposals
 * (ADR-0028 WP5c), the way the planner's contract says a tripped circuit breaker, a dry run or an
 * unattended-apply that is off must end: for a person to decide.
 *
 * <p>The only thing it can do is call {@link MemoryProposalSink#propose}, which creates a PENDING
 * (QUARANTINED-trust) row and has no path to ACTIVE or TRUSTED. Each proposal names its sources (the
 * lineage the store records, taking the lowest source trust, the union of taint and
 * {@code max(generation)+1}) and carries a consolidation writer label so that a person's approval, in
 * {@code MemoryItemReviewService#approve}, also supersedes those sources; nothing here supersedes
 * anything. No model is called: a merge proposes the keeper's own text, a summary is the sources'
 * texts joined and shortened, which a person can edit before approving. Returns how many proposals
 * the store accepted (it refuses when the feature is off, a queue is full or a source is unusable).
 */
@Component
public class ConsolidationProposalFiler {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationProposalFiler.class);

    /** Room for the prefix inside the store's {@code max-item-chars} (default 1200). */
    static final int MAX_SUMMARY_CHARS = 1100;

    private static final int MAX_PART_CHARS = 180;

    private final ObjectProvider<MemoryProposalSink> sink;

    public ConsolidationProposalFiler(ObjectProvider<MemoryProposalSink> sink) {
        this.sink = sink;
    }

    /** Proposes keeping {@code keeper}'s text in place of the keeper and its duplicates. 1 when filed, else 0. */
    int fileMerge(ConsolidationPlan.Merge merge, Map<UUID, ConsolidationItemSource.Item> items) {
        ConsolidationItemSource.Item keeper = items.get(merge.keepId());
        if (keeper == null) {
            return 0;
        }
        List<UUID> sources = new ArrayList<>();
        sources.add(keeper.id());
        sources.addAll(merge.dropIds());
        return file(
                keeper.agentId(),
                keeper.kind(),
                keeper.content(),
                sources,
                MemoryProvenanceRecord.WRITER_CONSOLIDATION_MERGE);
    }

    /** Proposes one summary of the plan's source items. 1 when filed, else 0. */
    int fileSummary(ConsolidationPlan.Summarise summary, Map<UUID, ConsolidationItemSource.Item> items) {
        List<String> parts = new ArrayList<>();
        MemoryKind kind;
        try {
            kind = MemoryKind.valueOf(summary.kind().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return 0;
        }
        for (UUID id : summary.sourceIds()) {
            ConsolidationItemSource.Item item = items.get(id);
            if (item == null) {
                return 0;
            }
            parts.add(shorten(item.content()));
        }
        return file(
                summary.agentId(),
                kind,
                summaryText(parts),
                summary.sourceIds(),
                MemoryProvenanceRecord.WRITER_CONSOLIDATION_SUMMARY);
    }

    /** "Summary of N notes: a; b; c", never longer than {@link #MAX_SUMMARY_CHARS}. */
    static String summaryText(List<String> parts) {
        StringBuilder sb = new StringBuilder("Summary of ").append(parts.size()).append(" notes: ");
        Set<String> seen = new LinkedHashSet<>();
        for (String part : parts) {
            if (part != null && !part.isBlank() && seen.add(part)) {
                String next = (seen.size() > 1 ? "; " : "") + part;
                if (sb.length() + next.length() > MAX_SUMMARY_CHARS) {
                    sb.append(seen.size() > 1 ? "; ..." : "...");
                    break;
                }
                sb.append(next);
            }
        }
        return sb.toString();
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return t.length() > MAX_PART_CHARS ? t.substring(0, MAX_PART_CHARS - 3) + "..." : t;
    }

    private int file(UUID agentId, MemoryKind kind, String text, List<UUID> sourceIds, String writer) {
        MemoryProposalSink s = sink.getIfAvailable();
        if (s == null || text == null || text.isBlank() || sourceIds.size() < 2) {
            return 0;
        }
        try {
            List<MemorySourceRef> sources = sourceIds.stream().map(MemorySourceRef::item).toList();
            Optional<?> receipt = s.propose(new MemoryWriteProposal(
                    agentId,
                    null,
                    kind,
                    text,
                    new MemoryProvenanceRecord(MemoryProvenanceRecord.TRUSTED.trust(), List.of(), writer),
                    null,
                    true,
                    sources));
            return receipt.isPresent() ? 1 : 0;
        } catch (RuntimeException e) {
            log.warn("Could not file one consolidation proposal: {}", e.getMessage());
            return 0;
        }
    }
}
