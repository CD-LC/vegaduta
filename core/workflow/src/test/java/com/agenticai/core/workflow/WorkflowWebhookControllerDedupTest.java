package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * Audit item #21 - inbound webhook triggers had no delivery-id/idempotency check at all, so a
 * webhook sender's at-least-once redelivery (a Zapier retry on a slow response, a cron-job.org
 * timeout retry) started a SECOND full workflow run for the same event. Covers the {@link
 * InboundMessageDedupService} claim now sitting in front of {@code WorkflowService#run} on {@code
 * POST /webhook/workflows/{token}}, reusing the same {@code SETNX}-with-TTL idiom already proven by
 * {@code InboundMessageDedupServiceTest}. Follows this module's own convention of calling the
 * controller method directly rather than through MockMvc (see {@code
 * WorkflowTriggerEndpointAdminGateTest}'s javadoc).
 */
class WorkflowWebhookControllerDedupTest {

    private static final String TOKEN = "raw-webhook-token";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private TenantResolutionService tenantResolutionService;
    private WorkflowDefinitionRepository definitionRepository;
    private WorkflowTriggerNodeService workflowTriggerNodeService;
    private WorkflowService workflowService;
    private InboundMessageDedupService dedupService;
    private WorkflowWebhookController controller;

    @BeforeEach
    void setUp() {
        tenantResolutionService = mock(TenantResolutionService.class);
        definitionRepository = mock(WorkflowDefinitionRepository.class);
        workflowTriggerNodeService = mock(WorkflowTriggerNodeService.class);
        workflowService = mock(WorkflowService.class);
        dedupService = mock(InboundMessageDedupService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<JwtDecoder> jwtDecoderProvider = mock(ObjectProvider.class);

        controller =
                new WorkflowWebhookController(
                        tenantResolutionService,
                        definitionRepository,
                        workflowTriggerNodeService,
                        workflowService,
                        com.agenticai.core.json.JsonMappers.plain(),
                        jwtDecoderProvider,
                        dedupService);

        // Resolve path hashes the inbound token before the lookup (L4 hardening), so the
        // link table is keyed by hash, not plaintext.
        when(tenantResolutionService.resolveTenantSchema(
                        ExternalAccountProvider.WORKFLOW_WEBHOOK,
                        WebhookTokenHasher.sha256Hex(TOKEN)))
                .thenReturn(Optional.of("tenant_schema"));

        WorkflowTriggerNode triggerNode =
                new WorkflowTriggerNode(WORKFLOW_ID, "node-1", WorkflowTriggerChannel.WEBHOOK);
        triggerNode.enableWebhook("cipher", WebhookTokenHasher.sha256Hex(TOKEN));
        when(workflowTriggerNodeService.findByWebhookTokenHash(anyString()))
                .thenReturn(Optional.of(triggerNode));

        WorkflowDefinition definition = mock(WorkflowDefinition.class);
        when(definition.getId()).thenReturn(WORKFLOW_ID);
        when(definition.getStatus()).thenReturn(WorkflowStatus.ACTIVE);
        when(definitionRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));

        when(workflowService.run(any(), anyString(), anyString(), anyString()))
                .thenReturn(mock(WorkflowRunResponse.class));
    }

    @AfterEach
    void tearDown() {
        com.agenticai.core.security.TenantContext.clear();
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(name, value);
        return headers;
    }

    private static byte[] json(Object value) {
        try {
            return com.agenticai.core.json.JsonMappers.plain().writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void firstDeliveryClaimsDedupAndStartsARun() {
        when(dedupService.tryClaim(eq("WORKFLOW_WEBHOOK"), anyString())).thenReturn(true);

        ResponseEntity<?> response =
                controller.trigger(
                        TOKEN, headers("X-Delivery-Id", "delivery-1"), json(Map.of("input", "hi")));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(workflowService).run(eq(WORKFLOW_ID), eq("node-1"), anyString(), anyString());
    }

    @Test
    void duplicateDeliveryIdIsRejectedWithoutStartingASecondRun() {
        when(dedupService.tryClaim(eq("WORKFLOW_WEBHOOK"), anyString())).thenReturn(false);

        ResponseEntity<?> response =
                controller.trigger(
                        TOKEN, headers("X-Delivery-Id", "delivery-1"), json(Map.of("input", "hi")));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(workflowService, never()).run(any(), anyString(), anyString(), anyString());
    }

    @Test
    void dedupKeyUsesTheDeliveryIdHeaderWhenPresent() {
        when(dedupService.tryClaim(anyString(), anyString())).thenReturn(true);

        controller.trigger(
                TOKEN, headers("X-Delivery-Id", "my-delivery-id"), json(Map.of("input", "hi")));

        verify(dedupService).tryClaim("WORKFLOW_WEBHOOK", "node-1:my-delivery-id");
    }

    @Test
    void dedupKeyFallsBackToAContentHashWhenNoDeliveryHeaderIsPresent() {
        when(dedupService.tryClaim(anyString(), anyString())).thenReturn(true);

        controller.trigger(TOKEN, new HttpHeaders(), json(Map.of("input", "hi")));
        controller.trigger(TOKEN, new HttpHeaders(), json(Map.of("input", "hi")));

        // Same payload, no delivery-id header -> the content hash must be
        // stable so a byte-identical redelivery still dedups.
        verify(dedupService, org.mockito.Mockito.times(2))
                .tryClaim(eq("WORKFLOW_WEBHOOK"), anyString());
        org.mockito.ArgumentCaptor<String> keyCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(dedupService, org.mockito.Mockito.times(2))
                .tryClaim(eq("WORKFLOW_WEBHOOK"), keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }
}
