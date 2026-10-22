package com.agenticai.core.templates;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.core.TuningMode;
import com.agenticai.core.agents.guardrail.AgentGuardrailService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;

/**
 * Shared fixture for the {@link CommunityTemplateService} test classes: mocks, a REAL {@link
 * TemplateSnapshotService} (so create -&gt; store -&gt; import round-trips exercise the allow-list
 * snapshot and its reconstruction), and template/caller builders. Plain JUnit5 + Mockito, no Spring
 * context.
 */
abstract class CommunityTemplateServiceTestBase {

    final CommunityTemplateRepository repository = mock(CommunityTemplateRepository.class);
    final CommunityTemplateRatingRepository ratingRepository =
            mock(CommunityTemplateRatingRepository.class);
    final AuditLogService auditLogService = mock(AuditLogService.class);
    final AgentService agentService = mock(AgentService.class);
    final AgentRepository agentRepository = mock(AgentRepository.class);
    final WorkflowService workflowService = mock(WorkflowService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final TemplateSnapshotService snapshotService = new TemplateSnapshotService(mapper);
    // A Mockito mock's void assertWithinDailyCap is a no-op - the daily import
    // cap never trips in these tests, which exercise the lifecycle, not the limiter.
    final TemplateImportRateLimiter importRateLimiter = mock(TemplateImportRateLimiter.class);

    // Import applies a template's tool allow-list and guardrails after create,
    // through the same public services the UI uses. A mock is right here: the
    // lifecycle assertions below do not depend on guardrail plan-gating, and
    // applyPostCreateDesign is explicitly best-effort so a no-op mock matches
    // the "template declared none" path these fixtures exercise.
    final AgentGuardrailService agentGuardrailService = mock(AgentGuardrailService.class);

    // These tests exercise the lifecycle, not the quality bar - the gate itself is covered by
    // TemplateSubmitGateTest and TemplateReadinessServiceTest. Here it always says "ready".
    final TemplateReadinessService readinessService = readyReadiness();
    final TemplateAiReviewService aiReviewService = passingAiReview();

    final CommunityTemplateService service =
            new CommunityTemplateService(
                    repository,
                    ratingRepository,
                    snapshotService,
                    auditLogService,
                    mapper,
                    agentService,
                    agentGuardrailService,
                    agentRepository,
                    workflowService,
                    importRateLimiter,
                    readinessService,
                    aiReviewService,
                    25);

    static TemplateReadinessService readyReadiness() {
        TemplateReadinessService m = mock(TemplateReadinessService.class);
        when(m.assess(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new com.agenticai.core.templates.dto.TemplateReadinessReport(
                                true,
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of()));
        return m;
    }

    static TemplateAiReviewService passingAiReview() {
        TemplateAiReviewService m = mock(TemplateAiReviewService.class);
        when(m.review(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new com.agenticai.core.templates.dto.TemplateAiReview(
                                com.agenticai.core.templates.dto.TemplateAiReview.Status.PASSED,
                                "ok",
                                java.util.List.of(),
                                java.util.List.of()));
        return m;
    }

    static final String OWNER = "user-owner-1";
    static final String ADMIN = "user-admin-9";
    static final String TENANT = "tenant-abc";

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ------------------------------- helpers ---------------------------------

    void asMember(String subject) {
        TenantContext.set(TenantContext.of(TENANT, subject, Set.of("ROLE_user")));
    }

    void asAdmin(String subject) {
        // SEC-010: moderation is platform-admin only.
        TenantContext.set(TenantContext.of(TENANT, subject, Set.of("ROLE_platform-admin")));
    }

    /**
     * A tenant admin holding EVERY tenant permission (TENANT_ADMIN bundle =
     * EnumSet.allOf(Permission)).
     */
    void asTenantAdminWithAllPermissions(String subject) {
        TenantContext.set(
                TenantContext.of(
                        TENANT,
                        subject,
                        Set.of(
                                "ROLE_tenant-admin",
                                "PERM_MANAGE_TEMPLATES",
                                "PERM_MANAGE_USERS",
                                "PERM_VIEW_AUDIT_LOG",
                                "PERM_VIEW_USAGE",
                                "PERM_MANAGE_AGENTS",
                                "PERM_MANAGE_TOOLS")));
    }

    CommunityTemplate draft(String owner, TemplateStatus status) {
        CommunityTemplate t =
                new CommunityTemplate(
                        TemplateKind.AGENT,
                        "My template",
                        "summary",
                        "support",
                        null,
                        "{}",
                        TENANT,
                        owner);
        forceStatus(t, status);
        setId(t);
        return t;
    }

    CommunityTemplate agentTemplate(TemplateStatus status) {
        String config = snapshotService.snapshotAgent(agentWithId(UUID.randomUUID()));
        CommunityTemplate t =
                new CommunityTemplate(
                        TemplateKind.AGENT,
                        "Returns Bot",
                        "summary",
                        "support",
                        null,
                        config,
                        TENANT,
                        OWNER);
        forceStatus(t, status);
        setId(t);
        return t;
    }

    /**
     * agentTemplate(), but from a caller-configured Agent so a test can pin autonomy/allow-list.
     */
    CommunityTemplate agentTemplateFrom(Agent agent, TemplateStatus status) {
        CommunityTemplate t =
                new CommunityTemplate(
                        TemplateKind.AGENT,
                        "Returns Bot",
                        "summary",
                        "support",
                        null,
                        snapshotService.snapshotAgent(agent),
                        TENANT,
                        OWNER);
        forceStatus(t, status);
        setId(t);
        return t;
    }

    CommunityTemplate workflowTemplate(TemplateStatus status) {
        WorkflowDefinition def =
                new WorkflowDefinition(
                        "Order triage",
                        "desc",
                        "{\"nodes\":[],\"edges\":[]}",
                        WorkflowStatus.ACTIVE);
        String config = snapshotService.snapshotWorkflow(def);
        CommunityTemplate t =
                new CommunityTemplate(
                        TemplateKind.WORKFLOW,
                        "Order triage",
                        "summary",
                        "ops",
                        null,
                        config,
                        TENANT,
                        OWNER);
        forceStatus(t, status);
        setId(t);
        return t;
    }

    static Agent agentWithId(UUID id) {
        Agent agent =
                new Agent(
                        "Returns Bot",
                        "You are a returns assistant.",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-4-5",
                        2048,
                        0.4,
                        0.9,
                        true,
                        ExperienceMode.ADVANCED,
                        ReasoningDepth.FAST,
                        TuningMode.MANUAL);
        setField(agent, "id", id);
        return agent;
    }

    static void forceStatus(CommunityTemplate t, TemplateStatus status) {
        setField(t, "status", status);
        if (status == TemplateStatus.PENDING || status == TemplateStatus.APPROVED) {
            setField(t, "submittedAt", java.time.Instant.now());
        }
    }

    static void setId(CommunityTemplate t) {
        if (t.getId() == null) {
            setField(t, "id", UUID.randomUUID());
        }
    }

    static void setField(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
