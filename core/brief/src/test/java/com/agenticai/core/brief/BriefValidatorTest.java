package com.agenticai.core.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.brief.model.Brief.SecretSpec;
import com.agenticai.core.brief.model.Brief.WorkflowSpec;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.workflow.OrchestratorService;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the REAL {@link OrchestratorService} (only its collaborators are mocked) so the
 * placeholder-id mapping is genuinely exercised: the mocked AgentService rejects EVERY agent id, so
 * a brief passes only if refs are mapped to placeholders that the validate overload treats as
 * existing.
 */
class BriefValidatorTest {

    private final AgentService agentService = mock(AgentService.class);
    private final WorkflowService workflowService = mock(WorkflowService.class);
    private final TenantLimits tenantLimits = mock(TenantLimits.class);
    private BriefValidator validator;

    @BeforeEach
    void setUp() {
        doThrow(new AgentNotFoundException(UUID.randomUUID()))
                .when(agentService)
                .requireExists(any(UUID.class));
        OrchestratorService orchestrator =
                new OrchestratorService(
                        agentService,
                        null,
                        com.agenticai.core.json.JsonMappers.plain(),
                        mock(ObjectProvider.class));
        when(tenantLimits.maxAgents("t1")).thenReturn(Optional.empty());
        when(tenantLimits.maxWorkflows("t1")).thenReturn(Optional.empty());
        validator =
                new BriefValidator(
                        orchestrator,
                        tenantLimits,
                        agentService,
                        workflowService,
                        com.agenticai.core.json.JsonMappers.plain());
        TenantContext.set(
                TenantContext.of(
                        "t1", "user-1", Set.of("PERM_MANAGE_AGENTS", "PERM_MANAGE_INTEGRATIONS")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static List<String> messages(BriefValidationResult r) {
        return r.errors().stream().map(Issue::toString).toList();
    }

    @Test
    void validBriefPassesAndGraphAgentRefsAreMappedToPlaceholders() {
        BriefValidationResult result = validator.validate(BriefFixtures.valid().build());
        assertThat(result.errors()).isEmpty();
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void agentReferencingUnknownKnowledgeRefIsRejected() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.agents = new ArrayList<>(List.of(BriefFixtures.agent("a1", List.of("k9"))));
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("agents[a1].knowledgeRefs") && m.contains("k9"));
    }

    @Test
    void workflowNodePointingAtUndeclaredAgentRefIsRejected() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.workflows = new ArrayList<>(List.of(BriefFixtures.workflow("w1", "a9", "k1")));
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("workflows[w1].graph") && m.contains("a9"));
    }

    @Test
    void workflowNodeCarryingARealUuidIsRejected() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.workflows =
                new ArrayList<>(
                        List.of(BriefFixtures.workflow("w1", UUID.randomUUID().toString(), "k1")));
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("never by id"));
    }

    @Test
    void structuralGraphErrorsComeFromTheRealOrchestratorGate() {
        BriefFixtures.B b = BriefFixtures.valid();
        WorkflowGraphDto noTrigger =
                new WorkflowGraphDto(
                        List.of(BriefFixtures.node("n2", "agent", Map.of("agentId", "a1"))),
                        List.of());
        b.workflows =
                new ArrayList<>(
                        List.of(new WorkflowSpec("w1", "Chase", noTrigger, List.of(), null)));
        b.trust = new Brief.Trust("UNDER_WATCH", List.of(), List.of());
        b.perNode = BriefFixtures.narrative("a1", "w1", "k1", "s1");
        b.narrative = new Brief.Narrative("why", b.perNode);
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("workflows[w1].graph") && m.contains("Trigger"));
    }

    @Test
    void everyArtifactNeedsANarrativeEntry() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.perNode = BriefFixtures.narrative("a1", "w1", "s1", "w1.n3"); // k1 missing
        b.narrative = new Brief.Narrative("why", b.perNode);
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("narrative.perNode") && m.contains("'k1'"));
    }

    @Test
    void narrativeEntriesNeedWhyTuneAndProof() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.perNode = new java.util.LinkedHashMap<>(b.perNode);
        b.perNode.put("a1", new Brief.NodeNarrative("why", "", "proof"));
        b.narrative = new Brief.Narrative("why", b.perNode);
        assertThat(messages(validator.validate(b.build())))
                .anyMatch(m -> m.contains("narrative.perNode.a1"));
    }

    @Test
    void irreversiblePathsMustPointAtRealWorkflowNodes() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.trust =
                new Brief.Trust("UNDER_WATCH", List.of("w1.n99", "w2.n1", "not-a-path"), List.of());
        List<String> errors = messages(validator.validate(b.build()));
        assertThat(errors).anyMatch(m -> m.contains("w1.n99") && m.contains("does not exist"));
        assertThat(errors).anyMatch(m -> m.contains("w2.n1") && m.contains("does not declare"));
        assertThat(errors).anyMatch(m -> m.contains("not-a-path"));
    }

    @Test
    void secretNamesFollowTheVaultPatternAndNeverCarryAValue() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.secrets =
                new ArrayList<>(
                        List.of(
                                new SecretSpec("s1", "tally_api_key", "TENANT", "why", null),
                                new SecretSpec(
                                        "s2",
                                        "OTHER_KEY",
                                        "TENANT",
                                        "why",
                                        "sk-abcdefghijklmnopqrstuvwxyz0123456789")));
        b.perNode = BriefFixtures.narrative("a1", "w1", "k1", "s1", "s2", "w1.n3");
        b.narrative = new Brief.Narrative("why", b.perNode);
        List<String> errors = messages(validator.validate(b.build()));
        assertThat(errors).anyMatch(m -> m.contains("secrets[s1].name"));
        assertThat(errors)
                .anyMatch(m -> m.contains("secrets[s2].obtainAt") && m.contains("credential"));
    }

    @Test
    void refsMustBeUniqueAndPrefixedByKind() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.agents =
                new ArrayList<>(
                        List.of(
                                BriefFixtures.agent("a1", List.of()),
                                BriefFixtures.agent("a1", List.of()),
                                BriefFixtures.agent("x1", List.of())));
        List<String> errors = messages(validator.validate(b.build()));
        assertThat(errors).anyMatch(m -> m.contains("Duplicate ref 'a1'"));
        assertThat(errors).anyMatch(m -> m.contains("must start with 'a'"));
    }

    @Test
    void planCapPrecheckCountsExistingPlusNewAgents() {
        when(tenantLimits.maxAgents("t1")).thenReturn(Optional.of(2));
        when(agentService.listAll())
                .thenReturn(
                        List.of(
                                mock(com.agenticai.core.agents.dto.AgentResponse.class),
                                mock(com.agenticai.core.agents.dto.AgentResponse.class)));
        List<String> errors = messages(validator.validate(BriefFixtures.valid().build()));
        assertThat(errors)
                .anyMatch(m -> m.startsWith("limits:") && m.contains("limit of 2 agents"));
        // an already-applied brief skips the cap check (its own agents would be double counted)
        assertThat(validator.validate(BriefFixtures.valid().build(), false).errors()).isEmpty();
    }

    @Test
    void missingIntegrationsPermissionIsReportedNotFatal() {
        TenantContext.set(TenantContext.of("t1", "user-1", Set.of("PERM_MANAGE_AGENTS")));
        BriefValidationResult result = validator.validate(BriefFixtures.valid().build());
        assertThat(result.errors()).isEmpty();
        assertThat(result.missingPermissions()).containsExactly("PERM_MANAGE_INTEGRATIONS");

        TenantContext.set(TenantContext.of("t1", "user-1", Set.of("PERM_MANAGE_INTEGRATIONS")));
        assertThat(validator.validate(BriefFixtures.valid().build()).missingPermissions())
                .isEmpty();
    }

    @Test
    void clarificationOnlyBriefIsCheckedForItsQuestionsAndHeaderOnly() {
        Brief asking =
                new Brief(
                        1,
                        "Something",
                        "Chase invoices somehow please",
                        "en",
                        List.of(
                                new Brief.Clarification(
                                        "Which system holds your invoices?",
                                        List.of("Tally", "Zoho"))),
                        new Brief.Trust("UNDER_WATCH", List.of(), List.of()),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null);
        assertThat(validator.validate(asking).errors()).isEmpty();
        assertThat(asking.isClarificationOnly()).isTrue();
    }

    @Test
    void briefSurvivesAJsonRoundTripAndIgnoresUnknownProperties() throws Exception {
        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        Brief original = BriefFixtures.valid().build();
        String json = mapper.writeValueAsString(original);
        assertThat(mapper.readValue(json, Brief.class)).isEqualTo(original);
        // a stray model-invented field is dropped, not fatal
        String withExtra = json.replaceFirst("\\{", "{\"invented\":true,");
        assertThat(mapper.readValue(withExtra, Brief.class)).isEqualTo(original);
    }

    @Test
    void unknownRungAndBadHeaderAreReported() {
        BriefFixtures.B b = BriefFixtures.valid();
        b.trust = new Brief.Trust("GODMODE", List.of(), List.of());
        b.title = "x";
        b.locale = "English";
        List<String> errors = messages(validator.validate(b.build()));
        assertThat(errors).anyMatch(m -> m.startsWith("trust.rung"));
        assertThat(errors).anyMatch(m -> m.startsWith("title"));
        assertThat(errors).anyMatch(m -> m.startsWith("locale"));
    }
}
