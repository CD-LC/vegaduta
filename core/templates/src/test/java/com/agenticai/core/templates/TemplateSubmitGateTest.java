package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.templates.dto.TemplateAiReview;
import com.agenticai.core.templates.dto.TemplateReadinessReport.Issue;
import com.agenticai.core.workflow.WorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The submit gate end to end with the REAL rule engine: blockers stop it, the AI layer runs only
 * after they pass.
 */
class TemplateSubmitGateTest {

    private final CommunityTemplateRepository repository = mock(CommunityTemplateRepository.class);
    private final TemplateAiReviewService ai = mock(TemplateAiReviewService.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TemplateSnapshotService snapshot = new TemplateSnapshotService(mapper);
    private final CommunityTemplateService service =
            new CommunityTemplateService(
                    repository,
                    mock(CommunityTemplateRatingRepository.class),
                    snapshot,
                    mock(AuditLogService.class),
                    mapper,
                    mock(AgentService.class),
                    mock(AgentGuardrailService.class),
                    mock(AgentRepository.class),
                    mock(WorkflowService.class),
                    mock(TemplateImportRateLimiter.class),
                    new TemplateReadinessService(),
                    ai,
                    25);

    @AfterEach
    void clear() {
        com.agenticai.core.security.TenantContext.clear();
    }

    private CommunityTemplate template(String summary, List<String> tags, String prompt)
            throws Exception {
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of(
                        "tenant-1", "member-1", java.util.Set.of("ROLE_user")));
        String config =
                mapper.writeValueAsString(
                        mapper.readTree(
                                "{\"systemPrompt\":"
                                        + mapper.writeValueAsString(prompt)
                                        + ",\"providerName\":\"PLATFORM_HOSTED\",\"maxTokens\":1024,\"autonomyTier\":\"SUPERVISED\","
                                        + "\"allowedToolNames\":[\"mcp__office-ally__check_claim_status\"],\"enabledGuardrails\":[\"jailbreak\"]}"));
        CommunityTemplate t =
                new CommunityTemplate(
                        TemplateKind.AGENT,
                        "Claims Status Helper",
                        summary,
                        "ops",
                        snapshot.writeTags(tags),
                        config,
                        "tenant-1",
                        "member-1");
        try {
            java.lang.reflect.Field f = CommunityTemplate.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(repository.findById(any())).thenReturn(Optional.of(t));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return t;
    }

    private static final String GOOD_SUMMARY =
            "Checks insurance claim status for your billing team and explains denials in plain"
                    + " language, without submitting anything.";

    @Test
    void ruleBlockers_stopSubmission_andNoModelCallIsMade() throws Exception {
        CommunityTemplate t = template("short", List.of(), TemplateStandard.EXAMPLE_PROMPT);
        assertThatThrownBy(() -> service.submit(UUID.randomUUID()))
                .isInstanceOf(TemplateNotReadyException.class)
                .satisfies(
                        e ->
                                assertThat(((TemplateNotReadyException) e).getReport().blockers())
                                        .extracting(Issue::code)
                                        .contains("SUMMARY_TOO_SHORT", "NO_TAGS"));
        verify(ai, never()).review(any(), any(), any(), any(), any(), any(), any());
        assertThat(t.getStatus()).isEqualTo(TemplateStatus.DRAFT);
    }

    @Test
    void aiBlocker_stopsSubmission() throws Exception {
        template(
                GOOD_SUMMARY, List.of("claims", "billing", "rcm"), TemplateStandard.EXAMPLE_PROMPT);
        when(ai.review(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TemplateAiReview(
                                TemplateAiReview.Status.ISSUES,
                                "mismatch",
                                List.of(
                                        new Issue(
                                                "AI_PURPOSE_MISMATCH",
                                                "Honest capability claims",
                                                "Prompt is a planner, listing says claims.",
                                                "Align them.")),
                                List.of()));
        assertThatThrownBy(() -> service.submit(UUID.randomUUID()))
                .isInstanceOf(TemplateNotReadyException.class)
                .satisfies(
                        e ->
                                assertThat(((TemplateNotReadyException) e).getReport().blockers())
                                        .extracting(Issue::code)
                                        .contains("AI_PURPOSE_MISMATCH"));
    }

    @Test
    void passingTemplate_goesPending_withReviewStored_andRequirementTagsMerged() throws Exception {
        CommunityTemplate t =
                template(
                        GOOD_SUMMARY,
                        List.of("claims", "billing", "rcm"),
                        TemplateStandard.EXAMPLE_PROMPT);
        when(ai.review(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TemplateAiReview(
                                TemplateAiReview.Status.PASSED, "fine", List.of(), List.of()));

        var response = service.submit(UUID.randomUUID());

        assertThat(response.status()).isEqualTo(TemplateStatus.PENDING);
        assertThat(t.getAiReviewJson()).contains("\"PASSED\"");
        assertThat(response.tags()).contains("claims", "mcp:office-ally");
        assertThat(response.review()).isNotNull();
        assertThat(response.requirements()).extracting(r -> r.id()).contains("office-ally");
    }

    @Test
    void aiOutage_doesNotBlock_andIsRecordedForTheAdmin() throws Exception {
        CommunityTemplate t =
                template(
                        GOOD_SUMMARY,
                        List.of("claims", "billing", "rcm"),
                        TemplateStandard.EXAMPLE_PROMPT);
        when(ai.review(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        TemplateAiReview.unavailable("The AI review was not available just now."));
        assertThat(service.submit(UUID.randomUUID()).status()).isEqualTo(TemplateStatus.PENDING);
        assertThat(t.getAiReviewJson()).contains("UNAVAILABLE");
    }
}
