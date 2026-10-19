package com.agenticai.core.agents.learning.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposal;
import com.agenticai.core.agents.learning.LearningProposalApplyException;
import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.skills.learning.SkillDraftScrubber;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.json.JsonMappers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MemoryConsolidationApplierTest {

    private static final String ME = "me";
    private final UUID agentId = UUID.randomUUID();
    private final UUID keep = UUID.randomUUID();
    private final UUID dup = UUID.randomUUID();
    private AgentMemoryService agent;
    private TenantMemoryService tenant;
    private GraphMemoryService graph;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private MemoryConsolidationApplier applier(boolean graphSync) {
        return new MemoryConsolidationApplier(provider(agent), provider(tenant), provider(graph), graphSync);
    }

    private static AgentMemoryEntry entry(UUID id, UUID agentId, String text) {
        return new AgentMemoryEntry(id, agentId, ME, text, 0, Instant.now(), Instant.now(), 0);
    }

    private static LearningProposal proposal(ConsolidationPayload payload) throws Exception {
        LearningProposal p = mock(LearningProposal.class);
        when(p.getSubject()).thenReturn(ME);
        when(p.getKind()).thenReturn(LearningProposalKind.CONSOLIDATION);
        when(p.getPayload()).thenReturn(JsonMappers.plain().writeValueAsString(payload.toMap()));
        return p;
    }

    @BeforeEach
    void setUp() {
        agent = mock(AgentMemoryService.class);
        tenant = mock(TenantMemoryService.class);
        graph = mock(GraphMemoryService.class);
        when(agent.listAllForSubject(ME))
                .thenReturn(List.of(entry(keep, agentId, "Prefers dark mode in every app, including the terminal"), entry(dup, agentId, "Prefers dark mode in every app")));
    }

    @Test
    void kindIsConsolidation() {
        assertEquals(LearningProposalKind.CONSOLIDATION, applier(false).kind());
    }

    @Test
    void mergeRewritesTheKeptFactAndForgetsTheOthers() throws Exception {
        when(agent.updateForSubject(eq(keep), eq(ME), anyString())).thenReturn(true);
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));

        applier(false).apply(p, "Prefers dark mode everywhere");

        verify(agent).updateForSubject(keep, ME, "Prefers dark mode everywhere");
        verify(agent).forgetForSubject(dup, ME);
    }

    @Test
    void refusesWhenAFactChangedSinceItWasProposed() throws Exception {
        Map<UUID, String> hashes =
                Map.of(
                        keep, ConsolidationPayload.fingerprint("Prefers light mode"),
                        dup, ConsolidationPayload.fingerprint("Prefers dark mode in every app"));
        LearningProposal p =
                proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup), hashes));

        LearningProposalApplyException e =
                assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "Prefers dark mode"));

        assertTrue(e.getMessage().contains("changed"));
        verify(agent, never()).updateForSubject(any(), anyString(), anyString());
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void appliesWhenEveryFingerprintStillMatches() throws Exception {
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        Map<UUID, String> hashes =
                Map.of(
                        keep, ConsolidationPayload.fingerprint("Prefers dark mode in every app, including the terminal"),
                        dup, ConsolidationPayload.fingerprint("Prefers dark mode in every app"));
        LearningProposal p =
                proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup), hashes));

        applier(false).apply(p, "Prefers dark mode in every app, including the terminal");

        verify(agent).forgetForSubject(dup, ME);
    }

    @Test
    void mergeWithUnchangedTextOnlyForgets() throws Exception {
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));

        applier(false).apply(p, "Prefers dark mode in every app, including the terminal");

        verify(agent, never()).updateForSubject(any(), anyString(), anyString());
        verify(agent).forgetForSubject(dup, ME);
    }

    @Test
    void dropForgetsOnlyAndNeverRewrites() throws Exception {
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        LearningProposal p = proposal(new ConsolidationPayload("drop", "agent", agentId, null, List.of(dup)));

        applier(false).apply(p, "ignored");

        verify(agent).forgetForSubject(dup, ME);
        verify(agent, never()).updateForSubject(any(), anyString(), anyString());
    }

    @Test
    void aFactThatAlreadyWentRefusesBeforeTouchingAnything() throws Exception {
        UUID gone = UUID.randomUUID();
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(gone)));

        assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "x y z"));

        verify(agent, never()).updateForSubject(any(), anyString(), anyString());
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void aFactOfAnotherAgentIsNotInTheTier() throws Exception {
        UUID otherAgent = UUID.randomUUID();
        LearningProposal p = proposal(new ConsolidationPayload("drop", "agent", otherAgent, null, List.of(dup)));

        assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "x"));
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void aRedactedEditWouldOverwriteTheRealMemoryAndIsRefused() throws Exception {
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));
        LearningProposalApplyException e =
                assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "Reach me at [EMAIL]"));
        assertTrue(e.getMessage().contains("redacted"));
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void aBlankMergedTextIsRefused() throws Exception {
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));
        assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "   "));
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void anUpdateThatFindsNoRowStopsBeforeAnyForget() throws Exception {
        when(agent.updateForSubject(eq(keep), eq(ME), anyString())).thenReturn(false);
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));
        assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "New wording of the note"));
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void anEmbeddingFailureBecomesATryAgainRefusal() throws Exception {
        when(agent.updateForSubject(eq(keep), eq(ME), anyString())).thenThrow(new IllegalStateException("sidecar down"));
        LearningProposal p = proposal(new ConsolidationPayload("merge", "agent", agentId, keep, List.of(dup)));
        LearningProposalApplyException e =
                assertThrows(LearningProposalApplyException.class, () -> applier(false).apply(p, "New wording of the note"));
        assertTrue(e.getMessage().contains("Try again"));
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void sharedScopeUsesTheSharedServiceOnly() throws Exception {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        when(tenant.listFactsForSubject(ME))
                .thenReturn(List.of(
                        new TenantMemoryEntry(s1, ME, "Works remotely", 0, null, Instant.now(), Instant.now(), 0),
                        new TenantMemoryEntry(s2, ME, "Works remotely most days", 0, null, Instant.now(), Instant.now(), 0)));
        when(tenant.forgetFactForSubject(s1, ME)).thenReturn(true);
        LearningProposal p = proposal(new ConsolidationPayload("merge", "shared", null, s2, List.of(s1)));

        applier(false).apply(p, "Works remotely most days");

        verify(tenant).forgetFactForSubject(s1, ME);
        verify(agent, never()).forgetForSubject(any(), anyString());
    }

    @Test
    void graphEdgesAreInvalidatedOnlyWhenTheForgetSyncFlagIsOn() throws Exception {
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        LearningProposal p = proposal(new ConsolidationPayload("drop", "agent", agentId, null, List.of(dup)));

        applier(false).apply(p, "x");
        verify(graph, never()).forgetFactText(anyString(), any(), anyString(), any());

        applier(true).apply(p, "x");
        verify(graph)
                .forgetFactText(
                        eq(ME),
                        eq(agentId),
                        eq("Prefers dark mode in every app"),
                        eq(List.of("Prefers dark mode in every app, including the terminal")));
    }

    @Test
    void aGraphFailureDoesNotUndoTheTidyUp() throws Exception {
        when(agent.forgetForSubject(dup, ME)).thenReturn(true);
        when(graph.forgetFactText(anyString(), any(), anyString(), any())).thenThrow(new IllegalStateException("age down"));
        LearningProposal p = proposal(new ConsolidationPayload("drop", "agent", agentId, null, List.of(dup)));
        applier(true).apply(p, "x");
        verify(agent).forgetForSubject(dup, ME);
    }

    @Test
    void memoryNotInstalledIsAPlainRefusal() throws Exception {
        MemoryConsolidationApplier none = new MemoryConsolidationApplier(provider(null), provider(null), provider(null), false);
        LearningProposal p = proposal(new ConsolidationPayload("drop", "agent", agentId, null, List.of(dup)));
        assertThrows(LearningProposalApplyException.class, () -> none.apply(p, "x"));
    }

    @Test
    void anIdThatLooksLikeACardNumberSurvivesTheProposalScrubber() {
        UUID digits = UUID.fromString("12345678-1234-1234-1234-123456789012");
        ConsolidationPayload payload = new ConsolidationPayload("drop", "shared", null, null, List.of(digits));
        String stored = SkillDraftScrubber.basic().scrub(payload.toMap().get("dropIds").toString());
        assertEquals(payload.toMap().get("dropIds").toString(), stored, "the scrubber must leave the encoded id alone");
        assertEquals(digits, ConsolidationPayload.decode(ConsolidationPayload.encode(digits)));
    }

    @Test
    void malformedPayloadsAreRejectedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> ConsolidationPayload.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> ConsolidationPayload.parse("{\"action\":\"drop\",\"scope\":\"shared\",\"dropIds\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> ConsolidationPayload.parse("{\"action\":\"merge\",\"scope\":\"shared\",\"dropIds\":[\"nope\"]}"));
    }
}
