package com.agenticai.core.agents.learning.consolidation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProposalStatus;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemorySourceRef;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memorystore.decay.ConsolidationPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Proposals the job files: the sink is the only door, lineage and the consolidation label ride along, no status is asked for. */
class ConsolidationProposalFilerTest {

    private final UUID agent = UUID.randomUUID();
    private final List<MemoryWriteProposal> seen = new ArrayList<>();
    private boolean accept = true;

    @SuppressWarnings("unchecked")
    private ConsolidationProposalFiler filer() {
        MemoryProposalSink sink = p -> {
            seen.add(p);
            return accept ? Optional.of(new MemoryProposalReceipt(UUID.randomUUID(), MemoryProposalStatus.PENDING)) : Optional.empty();
        };
        ObjectProvider<MemoryProposalSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sink);
        return new ConsolidationProposalFiler(provider);
    }

    private ConsolidationItemSource.Item item(MemoryKind kind, String text) {
        return new ConsolidationItemSource.Item(UUID.randomUUID(), agent, kind, text, 0, false, true, false, false, 0.5, 0,
                Instant.EPOCH, null, Instant.EPOCH);
    }

    @Test
    void aMergeProposesTheKeepersTextWithEverySourceInTheLineageAndTheMergeLabel() {
        var keep = item(MemoryKind.PROCEDURAL, "check the PSP first");
        var dup = item(MemoryKind.PROCEDURAL, "check the PSP first, always");
        Map<UUID, ConsolidationItemSource.Item> byId = new HashMap<>();
        byId.put(keep.id(), keep);
        byId.put(dup.id(), dup);

        int filed = filer().fileMerge(new ConsolidationPlan.Merge(keep.id(), List.of(dup.id())), byId);

        assertEquals(1, filed);
        MemoryWriteProposal p = seen.get(0);
        assertEquals("check the PSP first", p.text());
        assertEquals(MemoryKind.PROCEDURAL, p.kind());
        assertEquals(agent, p.agentId());
        assertEquals(MemoryProvenanceRecord.WRITER_CONSOLIDATION_MERGE, p.provenance().writer());
        assertEquals(MemoryTrustLevel.TRUSTED, p.provenance().trust(), "a label, not a status: the store decides PENDING");
        assertTrue(p.modelGenerated());
        assertEquals(List.of(keep.id().toString(), dup.id().toString()), p.sources().stream().map(MemorySourceRef::key).toList());
        assertTrue(p.sources().stream().allMatch(s -> MemorySourceRef.MEMORY_ITEM.equals(s.table())));
    }

    @Test
    void aSummaryJoinsAndShortensTheSourceTextsAndCarriesTheSummaryLabel() {
        var a = item(MemoryKind.EPISODIC, "episode one about  the roadmap");
        var b = item(MemoryKind.EPISODIC, "episode two about the budget");
        Map<UUID, ConsolidationItemSource.Item> byId = Map.of(a.id(), a, b.id(), b);
        var plan = new ConsolidationPlan.Summarise(List.of(a.id(), b.id()), agent, "episodic", MemoryTrustLevel.TRUSTED, 1, 0.4);

        assertEquals(1, filer().fileSummary(plan, byId));

        MemoryWriteProposal p = seen.get(0);
        assertEquals("Summary of 2 notes: episode one about the roadmap; episode two about the budget", p.text());
        assertEquals(MemoryKind.EPISODIC, p.kind());
        assertEquals(MemoryProvenanceRecord.WRITER_CONSOLIDATION_SUMMARY, p.provenance().writer());
        assertEquals(2, p.sources().size());
    }

    @Test
    void summaryTextIsBoundedAndDeduplicated() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            parts.add("a fairly long note number " + i + " " + "x".repeat(120));
        }
        String text = ConsolidationProposalFiler.summaryText(parts);
        assertTrue(text.length() <= ConsolidationProposalFiler.MAX_SUMMARY_CHARS + 5);
        assertTrue(text.endsWith("..."));
        assertEquals("Summary of 3 notes: same", ConsolidationProposalFiler.summaryText(List.of("same", "same", " ")));
    }

    @Test
    void aRefusingSinkOrAnUnknownSourceFilesNothing() {
        var keep = item(MemoryKind.PROCEDURAL, "keep");
        var dup = item(MemoryKind.PROCEDURAL, "keep too");
        accept = false;
        assertEquals(0, filer().fileMerge(new ConsolidationPlan.Merge(keep.id(), List.of(dup.id())),
                Map.of(keep.id(), keep, dup.id(), dup)));
        accept = true;
        assertEquals(0, filer().fileMerge(new ConsolidationPlan.Merge(keep.id(), List.of(dup.id())), Map.of()));
        assertEquals(0, filer().fileSummary(
                new ConsolidationPlan.Summarise(List.of(keep.id(), dup.id()), agent, "episodic", MemoryTrustLevel.TRUSTED, 1, 0.1),
                Map.of(keep.id(), keep)));
        assertEquals(0, filer().fileSummary(
                new ConsolidationPlan.Summarise(List.of(keep.id(), dup.id()), agent, "not_a_kind", MemoryTrustLevel.TRUSTED, 1, 0.1),
                Map.of(keep.id(), keep, dup.id(), dup)));
    }
}
