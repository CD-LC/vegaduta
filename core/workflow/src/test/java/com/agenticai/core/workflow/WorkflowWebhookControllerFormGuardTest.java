package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.dto.WorkflowRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * A hosted-form trigger node reuses the node's webhook token for its form URL, so that same token
 * must not also work as a raw webhook (it would skip the form's schema validation, honeypot and
 * limits). The guard is setter-injected: a controller built without it - and a deployment with the
 * form feature off - behave exactly as before.
 */
class WorkflowWebhookControllerFormGuardTest {

    private static final String TOKEN = "raw-webhook-token";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private WorkflowService workflowService;
    private WorkflowWebhookController controller;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() {
        TenantResolutionService tenantResolutionService = mock(TenantResolutionService.class);
        WorkflowDefinitionRepository definitionRepository =
                mock(WorkflowDefinitionRepository.class);
        WorkflowTriggerNodeService triggerNodes = mock(WorkflowTriggerNodeService.class);
        workflowService = mock(WorkflowService.class);
        InboundMessageDedupService dedup = mock(InboundMessageDedupService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JwtDecoder> jwt = mock(ObjectProvider.class);
        controller =
                new WorkflowWebhookController(
                        tenantResolutionService,
                        definitionRepository,
                        triggerNodes,
                        workflowService,
                        new ObjectMapper(),
                        jwt,
                        dedup);

        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.WORKFLOW_WEBHOOK,
                        WebhookTokenHasher.sha256Hex(TOKEN)))
                .thenReturn(Optional.of("tenant_schema"));
        WorkflowTriggerNode node =
                new WorkflowTriggerNode(WORKFLOW_ID, "node-1", WorkflowTriggerChannel.WEBHOOK);
        node.enableWebhook("cipher", WebhookTokenHasher.sha256Hex(TOKEN));
        when(triggerNodes.findByWebhookTokenHash(anyString())).thenReturn(Optional.of(node));
        definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WORKFLOW_ID);
        when(definition.getStatus()).thenReturn(WorkflowStatus.ACTIVE);
        when(definitionRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));
        when(dedup.tryClaim(anyString(), anyString())).thenReturn(true);
        when(workflowService.run(any(), anyString(), anyString(), anyString()))
                .thenReturn(mock(WorkflowRunResponse.class));
    }

    @AfterEach
    void tearDown() {
        com.agenticai.core.security.TenantContext.clear();
    }

    private static byte[] json(Object value) {
        try {
            return new ObjectMapper().writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theRawWebhookIsRefusedForAFormTriggerNode() {
        WorkflowFormService forms = mock(WorkflowFormService.class);
        when(forms.isFormTriggerNode(definition, "node-1")).thenReturn(true);
        controller.setFormService(forms);

        ResponseEntity<?> response =
                controller.trigger(TOKEN, new HttpHeaders(), json(Map.of("input", "hi")));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(workflowService, never()).run(any(), anyString(), anyString(), anyString());
    }

    @Test
    void anOrdinaryWebhookNodeIsUnaffected() {
        WorkflowFormService forms = mock(WorkflowFormService.class);
        when(forms.isFormTriggerNode(definition, "node-1")).thenReturn(false);
        controller.setFormService(forms);

        assertEquals(
                HttpStatus.OK,
                controller
                        .trigger(TOKEN, new HttpHeaders(), json(Map.of("input", "hi")))
                        .getStatusCode());
    }

    @Test
    void aControllerWithoutTheGuardBehavesAsBefore() {
        assertEquals(
                HttpStatus.OK,
                controller
                        .trigger(TOKEN, new HttpHeaders(), json(Map.of("input", "hi")))
                        .getStatusCode());
    }
}
