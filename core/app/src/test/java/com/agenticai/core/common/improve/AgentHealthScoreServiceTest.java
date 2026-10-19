package com.agenticai.core.common.improve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link AgentHealthScoreService} is a plain arithmetic reducer over the same
 * evidence {@link AgentImprovementService} already reads (OPEN {@code
 * PlatformIssue} clusters keyed to the agent, PENDING {@code
 * AgentImprovementSuggestion} rows) - these tests pin the formula (start at
 * 100, subtract per-cluster and per-pending-suggestion penalties, clamp to
 * [0, 100]) and the upsert semantics (one row per agent, reused on the next
 * tick rather than duplicated).
 */
class AgentHealthScoreServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final String TENANT_ID = "acme";

    private AgentHealthScoreRepository healthScoreRepository;
    private PlatformIssueRepository issueRepository;
    private AgentImprovementSuggestionRepository suggestionRepository;
    private AgentHealthScoreService service;

    @BeforeEach
    void setUp() {
        healthScoreRepository = mock(AgentHealthScoreRepository.class);
        issueRepository = mock(PlatformIssueRepository.class);
        suggestionRepository = mock(AgentImprovementSuggestionRepository.class);
        service = new AgentHealthScoreService(healthScoreRepository, issueRepository, suggestionRepository);

        when(healthScoreRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.empty());
        when(healthScoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(List.of());
        when(suggestionRepository.findTop100ByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of());
    }

    private static PlatformIssue issueFor(IssueSource source, IssueSeverity severity, int occurrences) {
        PlatformIssue issue = new PlatformIssue(
            UUID.randomUUID().toString(), source, AGENT_ID.toString(), severity, "title", "message");
        for (int i = 1; i < occurrences; i++) {
            issue.recordRecurrence("message");
        }
        return issue;
    }

    private static AgentImprovementSuggestion pendingSuggestion() {
        return new AgentImprovementSuggestion(
            TENANT_ID, AGENT_ID, UUID.randomUUID(), ImprovementKind.PROMPT_REVISION,
            "title", "rationale", "text");
    }

    @Test
    void noOpenIssuesAndNoPendingSuggestionsScoresPerfectHealthy() {
        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(100, snapshot.getScore());
        assertEquals(AgentHealthStatus.HEALTHY, snapshot.getStatus());
        assertEquals(0, snapshot.getOpenIssueCount());
        assertEquals(0, snapshot.getPendingSuggestionCount());
    }

    @Test
    void openErrorIssueKeyedToThisAgentLowersScoreMoreThanAWarning() {
        PlatformIssue errorIssue = issueFor(IssueSource.TOOL_CALL, IssueSeverity.ERROR, 1);
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(List.of(errorIssue));
        int scoreWithError = service.recompute(AGENT_ID, TENANT_ID).getScore();

        PlatformIssue warningIssue = issueFor(IssueSource.TOOL_CALL, IssueSeverity.WARNING, 1);
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(List.of(warningIssue));
        int scoreWithWarning = service.recompute(AGENT_ID, TENANT_ID).getScore();

        assertTrue(scoreWithError < scoreWithWarning,
            "an ERROR-severity cluster must cost more than a WARNING one, got error=" + scoreWithError
                + " warning=" + scoreWithWarning);
        assertTrue(scoreWithError < 100);
        assertTrue(scoreWithWarning < 100);
    }

    @Test
    void issuesNotKeyedToThisAgentAreIgnored() {
        PlatformIssue otherAgentIssue = new PlatformIssue(
            UUID.randomUUID().toString(), IssueSource.TOOL_CALL, UUID.randomUUID().toString(),
            IssueSeverity.ERROR, "title", "message");
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(List.of(otherAgentIssue));

        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(100, snapshot.getScore());
        assertEquals(0, snapshot.getOpenIssueCount());
    }

    @Test
    void nonAgentKeyedSourceIsIgnoredEvenWhenSourceIdMatches() {
        // e.g. IssueSource.ABUSE_ANOMALY is keyed to something other than an agent id;
        // AGENT_KEYED_SOURCES intentionally excludes it even if sourceId happened to collide.
        PlatformIssue abuseIssue = issueFor(IssueSource.ABUSE_ANOMALY, IssueSeverity.ERROR, 3);
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(List.of(abuseIssue));

        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(100, snapshot.getScore());
        assertEquals(0, snapshot.getOpenIssueCount());
    }

    @Test
    void pendingSuggestionsLowerTheScore() {
        when(suggestionRepository.findTop100ByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(pendingSuggestion(), pendingSuggestion()));

        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertTrue(snapshot.getScore() < 100);
        assertEquals(2, snapshot.getPendingSuggestionCount());
    }

    @Test
    void nonPendingSuggestionsDoNotCountAgainstTheScore() {
        AgentImprovementSuggestion dismissed = pendingSuggestion();
        dismissed.markDismissed("actor@example.com");
        when(suggestionRepository.findTop100ByAgentIdOrderByCreatedAtDesc(AGENT_ID))
            .thenReturn(List.of(dismissed));

        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(100, snapshot.getScore());
        assertEquals(0, snapshot.getPendingSuggestionCount());
    }

    @Test
    void scoreIsClampedAtZeroAndStatusIsDegraded() {
        List<PlatformIssue> manyErrors = List.of(
            issueFor(IssueSource.TOOL_CALL, IssueSeverity.ERROR, 20),
            issueFor(IssueSource.LLM_QUALITY, IssueSeverity.ERROR, 20),
            issueFor(IssueSource.PROVIDER_FALLBACK, IssueSeverity.ERROR, 20),
            // Three occurrence-capped ERRORs cost 90 points; the fourth is what forces the clamp.
            issueFor(IssueSource.TOOL_CALL, IssueSeverity.ERROR, 20));
        when(issueRepository.findTop200ByStatusOrderByLastSeenAtDesc(IssueStatus.OPEN))
            .thenReturn(manyErrors);

        AgentHealthScore snapshot = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(0, snapshot.getScore());
        assertEquals(AgentHealthStatus.DEGRADED, snapshot.getStatus());
    }

    @Test
    void secondRecomputeUpsertsTheSameRowInsteadOfCreatingANewOne() {
        AgentHealthScore existing = new AgentHealthScore(TENANT_ID, AGENT_ID);
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(healthScoreRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(existing));

        AgentHealthScore recomputed = service.recompute(AGENT_ID, TENANT_ID);

        assertEquals(existing.getId(), recomputed.getId());
    }

    @Test
    void getForAgentDelegatesToTheRepository() {
        AgentHealthScore existing = new AgentHealthScore(TENANT_ID, AGENT_ID);
        when(healthScoreRepository.findByAgentId(AGENT_ID)).thenReturn(Optional.of(existing));

        assertEquals(Optional.of(existing), service.getForAgent(AGENT_ID));
    }
}
