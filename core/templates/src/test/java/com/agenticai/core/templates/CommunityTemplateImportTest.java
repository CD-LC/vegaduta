package com.agenticai.core.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentAutonomyTier;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import com.agenticai.core.workflow.WorkflowDefinition;
import com.agenticai.core.workflow.WorkflowStatus;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link CommunityTemplateService#importTemplate}: access, instantiation and post-create design. */
class CommunityTemplateImportTest extends CommunityTemplateServiceTestBase {

    // ------------------------------- import ----------------------------------

    @Test
    void import_ofAnApprovedAgentTemplate_createsARealNewAgent() throws Exception {
        asMember("importer-user");
        CommunityTemplate template = agentTemplate(TemplateStatus.APPROVED);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID newAgentId = UUID.randomUUID();
        when(agentService.create(any())).thenReturn(AgentResponse.from(agentWithId(newAgentId)));

        TemplateImportResponse response = service.importTemplate(template.getId());

        assertThat(response.kind()).isEqualTo(TemplateKind.AGENT);
        assertThat(response.resourceId()).isEqualTo(newAgentId);
        // Went through AgentService.create (not a raw insert), import counted, and audited.
        verify(agentService).create(any());
        assertThat(template.getImportCount()).isEqualTo(1L);
        verify(auditLogService)
                .record(any(), eq("template.import"), eq("template"), any(), any(), any());
    }

    /**
     * Regression test for the 2026-09-12 safety bug: a template declaring SUPERVISED imported as
     * the SEMI_AUTONOMOUS default and did NOT wait for approval, silently breaking the promise its
     * own description makes ("every send waits for your approval"). Seven platform templates made
     * that promise, including the whole V223 real-world-action set that sends mail and books
     * meetings.
     *
     * <p>Nothing asserted this before, which is exactly why it shipped: AgentCreateRequest has no
     * autonomyTier field BY DESIGN, and nothing re-applied it after create. The fix is in
     * applyPostCreateDesign.
     */
    @Test
    void import_ofATemplateDeclaringSupervised_actuallyAppliesThatAutonomyTier() throws Exception {
        asMember("importer-user");
        Agent supervised = agentWithId(UUID.randomUUID());
        supervised.setAutonomyTier(AgentAutonomyTier.SUPERVISED);
        CommunityTemplate template = agentTemplateFrom(supervised, TemplateStatus.APPROVED);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID newAgentId = UUID.randomUUID();
        when(agentService.create(any())).thenReturn(AgentResponse.from(agentWithId(newAgentId)));

        service.importTemplate(template.getId());

        verify(agentService).setAutonomyTier(newAgentId, AgentAutonomyTier.SUPERVISED);
    }

    /**
     * The other half of the same fix. An EMPTY allowedToolNames on Agent means NO RESTRICTION, not
     * "no tools", so a template shipping [] hands the imported agent every tool in the deployment.
     * 'Financial / High-Risk Agent' shipped exactly that and was repaired in data by V226.
     *
     * <p>This pins the SEMANTICS rather than the repair: skipping an empty list stays correct (the
     * API cannot express "deny all" this way), so the guarantee worth testing is that we never call
     * setAllowedToolNames with an empty set - which would be read as "unrestricted" downstream
     * anyway - and that the import still succeeds. The service logs a WARN on this path so a
     * high-risk template importing unrestricted is never silent again.
     */
    @Test
    void import_ofATemplateWithAnEmptyToolAllowList_neverSetsAnEmptyRestriction() throws Exception {
        asMember("importer-user");
        Agent unrestricted = agentWithId(UUID.randomUUID());
        unrestricted.setAllowedToolNames(Set.of());
        CommunityTemplate template = agentTemplateFrom(unrestricted, TemplateStatus.APPROVED);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID newAgentId = UUID.randomUUID();
        when(agentService.create(any())).thenReturn(AgentResponse.from(agentWithId(newAgentId)));

        TemplateImportResponse response = service.importTemplate(template.getId());

        assertThat(response.resourceId()).isEqualTo(newAgentId);
        verify(agentService, never()).setAllowedToolNames(any(), eq(Set.of()));
    }

    @Test
    void import_ofAnApprovedWorkflowTemplate_createsARealNewWorkflow() throws Exception {
        asMember("importer-user");
        CommunityTemplate template = workflowTemplate(TemplateStatus.APPROVED);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID newWorkflowId = UUID.randomUUID();
        WorkflowDefinition created =
                new WorkflowDefinition(
                        "x", "y", "{\"nodes\":[],\"edges\":[]}", WorkflowStatus.DRAFT);
        setField(created, "id", newWorkflowId);
        when(workflowService.create(any(), any(), any())).thenReturn(created);

        TemplateImportResponse response = service.importTemplate(template.getId());

        assertThat(response.kind()).isEqualTo(TemplateKind.WORKFLOW);
        assertThat(response.resourceId()).isEqualTo(newWorkflowId);
        verify(workflowService).create(eq("Order triage"), any(), any());
    }

    @Test
    void import_ofANonApprovedTemplate_byANonOwner_is404() {
        asMember("outsider");
        CommunityTemplate template = agentTemplate(TemplateStatus.PENDING);
        when(repository.findById(template.getId())).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.importTemplate(template.getId()))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(agentService, never()).create(any());
    }
}
