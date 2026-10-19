package com.agenticai.core.agents.learning.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.learning.LearningProposalKind;
import com.agenticai.core.agents.learning.LearningProposalService;
import com.agenticai.core.agents.learning.LearningProposalService.Outcome;
import com.agenticai.core.agents.learning.LearningProposalService.Proposal;
import com.agenticai.core.agents.learning.LearningProposalService.Result;
import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class MemoryReviewServiceTest {

    private static final String ME = "me";
    private final UUID agentId = UUID.randomUUID();
    private LearningProposalService proposals;
    private LearningReviewRunRepository runs;
    private AgentMemoryService agent;
    private MemoryReviewService service;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static AgentMemoryEntry entry(UUID agent, String text, int createdDaysAgo, int usedDaysAgo) {
        Instant created = Instant.now().minus(Duration.ofDays(createdDaysAgo));
        Instant used = Instant.now().minus(Duration.ofDays(usedDaysAgo));
        return new AgentMemoryEntry(UUID.randomUUID(), agent, ME, text, 0, created, used, 0);
    }

    @BeforeEach
    void setUp() {
        proposals = mock(LearningProposalService.class);
        when(proposals.propose(any())).thenReturn(new Result(Outcome.CREATED, null));
        runs = mock(LearningReviewRunRepository.class);
        when(runs.claim(anyString(), any(LocalDate.class))).thenReturn(1);
        agent = mock(AgentMemoryService.class);
        service = new MemoryReviewService(proposals, runs, provider(agent), provider((TenantMemoryService) null), 60, 5, 100);
    }

    @Test
    void aPersonAlreadyReviewedTodayIsNotReadAgain() {
        when(runs.claim(anyString(), any(LocalDate.class))).thenReturn(0);
        assertEquals(0, service.reviewSubject(ME));
        verify(agent, never()).listAllForSubject(anyString());
        verify(proposals, never()).propose(any());
    }

    @Test
    void duplicatesAndStaleFactsBecomeConsolidationProposalsWithTheRightPayload() {
        AgentMemoryEntry shorter = entry(agentId, "Prefers dark mode in every app", 10, 1);
        AgentMemoryEntry longer = entry(agentId, "Prefers dark mode in every app, including the terminal", 5, 1);
        AgentMemoryEntry stale = entry(agentId, "Was reading a book about gardening", 300, 200);
        when(agent.listAllForSubject(ME)).thenReturn(List.of(shorter, longer, stale));

        assertEquals(2, service.reviewSubject(ME));

        ArgumentCaptor<Proposal> captor = ArgumentCaptor.forClass(Proposal.class);
        verify(proposals, org.mockito.Mockito.times(2)).propose(captor.capture());
        List<Proposal> filed = new ArrayList<>(captor.getAllValues());
        filed.forEach(p -> {
            assertEquals(LearningProposalKind.CONSOLIDATION, p.kind());
            assertEquals(ME, p.subject());
            assertEquals(agentId, p.agentId());
        });
        Proposal merge = filed.stream().filter(p -> "merge".equals(p.payload().get("action"))).findFirst().orElseThrow();
        assertEquals(longer.content(), merge.body(), "the more informative wording is what stays");
        ConsolidationPayload parsed = ConsolidationPayload.parse(toJson(merge));
        assertEquals(longer.id(), parsed.keepId());
        assertEquals(List.of(shorter.id()), parsed.dropIds());
        Proposal drop = filed.stream().filter(p -> "drop".equals(p.payload().get("action"))).findFirst().orElseThrow();
        assertEquals(stale.content(), drop.body());
        assertEquals("stale", drop.evidence().get("signal"));
        verify(runs).recordFiled(anyString(), any(LocalDate.class), org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void duplicateOutcomesFromTheStoreAreNotCountedAsFiled() {
        when(proposals.propose(any())).thenReturn(new Result(Outcome.DUPLICATE, null));
        when(agent.listAllForSubject(ME))
                .thenReturn(List.of(entry(agentId, "Prefers dark mode in every app", 10, 1), entry(agentId, "Prefers dark mode in every app", 5, 1)));
        assertEquals(0, service.reviewSubject(ME));
    }

    @Test
    void aNoteThatContainsPersonalDataIsNeverRewritten() {
        when(agent.listAllForSubject(ME))
                .thenReturn(List.of(
                        entry(agentId, "Contact for billing questions is jane.doe@example.com at the office", 10, 1),
                        entry(agentId, "Contact for billing questions is jane.doe@example.com at the office today", 5, 1)));
        assertEquals(0, service.reviewSubject(ME));
        verify(proposals, never()).propose(any());
    }

    @Test
    void withoutAnyMemoryServiceNothingHappens() {
        MemoryReviewService none = new MemoryReviewService(proposals, runs, provider((AgentMemoryService) null), provider((TenantMemoryService) null), 60, 5, 100);
        assertEquals(0, none.reviewTenant());
        verify(runs, never()).claim(anyString(), any(LocalDate.class));
    }

    @Test
    void reviewTenantWalksEveryPersonAndSurvivesOneFailing() {
        when(agent.listSubjects(anyInt())).thenReturn(List.of("a", "b"));
        when(agent.listAllForSubject("a")).thenThrow(new IllegalStateException("db"));
        when(agent.listAllForSubject("b"))
                .thenReturn(List.of(entry(agentId, "Prefers dark mode in every app", 10, 1), entry(agentId, "Prefers dark mode in every app, including the terminal", 5, 1)));
        assertEquals(1, service.reviewTenant());
    }

    @Test
    void titlesAndSnippetsStayShort() {
        String longText = "word ".repeat(100);
        assertTrue(MemoryReviewService.snippet(longText).length() <= 70);
        assertEquals("spaced out", MemoryReviewService.snippet("  spaced   out  "));
    }

    private static String toJson(Proposal p) {
        try {
            return com.agenticai.core.json.JsonMappers.plain().writeValueAsString(p.payload());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
