package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNode;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerNodeService;
import com.agenticai.core.omnichannel.trigger.WorkflowTriggerPort;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The public form's resolution, validation, honeypot, rate limits and run start. The token is the SAME opaque
 * webhook token the shared-secret webhook trigger uses; every "this form does not exist" cause collapses into one
 * indistinguishable outcome.
 */
class WorkflowFormServiceTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String NODE_ID = "t1";
    private static final String TOKEN = "a1b2c3d4".repeat(6);
    private static final String TOKEN_HASH = WebhookTokenHasher.sha256Hex(TOKEN);
    private static final String TOKEN_PREFIX = "agentic:workflow-form-token-rate:";
    private static final String IP_PREFIX = "agentic:workflow-form-ip-rate:";

    private TenantResolutionService tenantResolution;
    private WorkflowTriggerNodeService triggerNodes;
    private WorkflowDefinitionRepository definitions;
    private WorkflowService workflowService;
    private WorkflowTriggerPort triggerPort;
    private WorkflowPublicRateLimiter rateLimiter;
    private WorkflowFormService service;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        tenantResolution = mock(TenantResolutionService.class);
        triggerNodes = mock(WorkflowTriggerNodeService.class);
        definitions = mock(WorkflowDefinitionRepository.class);
        workflowService = mock(WorkflowService.class);
        triggerPort = mock(WorkflowTriggerPort.class);
        rateLimiter = mock(WorkflowPublicRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyString(), anyLong())).thenReturn(true);
        service = newService(true);

        when(tenantResolution.resolveTenantSchema(ExternalAccountProvider.WORKFLOW_WEBHOOK, TOKEN_HASH))
            .thenReturn(Optional.of("tenant_a"));
        when(triggerNodes.findByWebhookTokenHash(TOKEN_HASH)).thenReturn(Optional.of(enabledTriggerNode()));
        definition = definition(WorkflowStatus.ACTIVE);
        when(definitions.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));
        when(workflowService.resolveRunGraph(definition)).thenReturn(formGraph());
    }

    private WorkflowFormService newService(boolean enabled) {
        return new WorkflowFormService(tenantResolution, triggerNodes, definitions, workflowService, triggerPort,
            rateLimiter, new ObjectMapper(), enabled, 30, 8, 2_000);
    }

    private static WorkflowTriggerNode enabledTriggerNode() {
        WorkflowTriggerNode node = new WorkflowTriggerNode(WORKFLOW_ID, NODE_ID, WorkflowTriggerChannel.WEBHOOK);
        node.enableWebhook("encrypted", TOKEN_HASH);
        return node;
    }

    private static WorkflowDefinition definition(WorkflowStatus status) throws ReflectiveOperationException {
        WorkflowDefinition d = new WorkflowDefinition("Contact form", "d", "{}", status);
        java.lang.reflect.Field idField = WorkflowDefinition.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(d, WORKFLOW_ID);
        return d;
    }

    private static Map<String, Object> formConfig() {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "Contact us");
        form.put("description", "We reply within a day.");
        form.put("submitLabel", "Send");
        form.put("successMessage", "Thanks - we got it.");
        form.put("redirectUrl", "https://example.com/thanks");
        form.put("fields", List.of(
            Map.of("key", "name", "type", "text", "label", "Name", "required", true),
            Map.of("key", "agree", "type", "checkbox", "required", true)));
        Map<String, Object> config = new HashMap<>();
        config.put("triggerType", "form");
        config.put("form", form);
        return config;
    }

    private static WorkflowGraphDto graphWith(Map<String, Object> triggerConfig) {
        WorkflowNodeDto trigger = new WorkflowNodeDto(NODE_ID, "trigger", Map.of(),
            Map.of("config", triggerConfig, "label", "Form"));
        WorkflowNodeDto agent = new WorkflowNodeDto("a1", "agent", Map.of(), Map.of("config", Map.of(), "label", "A"));
        return new WorkflowGraphDto(List.of(trigger, agent), List.of(new WorkflowEdgeDto("e1", NODE_ID, "a1", null, null)));
    }

    private static WorkflowGraphDto formGraph() {
        return graphWith(formConfig());
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> validBody() {
        return body("values", body("name", "Asha", "agree", true), WorkflowFormSchema.HONEYPOT_FIELD, "");
    }

    private static void assertNoTenantLeak() {
        assertFalse(TenantContext.current().isPresent(), "TenantContext must always be cleared");
    }

    // ---- view -----------------------------------------------------------------------------------------------------

    @Test
    void viewReturnsThePublicDefinitionWithoutInternals() {
        Optional<WorkflowFormService.FormView> view = service.view(TOKEN);

        assertTrue(view.isPresent());
        assertEquals("Contact us", view.get().title());
        assertEquals("Send", view.get().submitLabel());
        assertEquals(2, view.get().fields().size());
        assertEquals("name", view.get().fields().get(0).key());
        assertTrue(view.get().fields().get(0).required());
        assertEquals(WorkflowFormSchema.HONEYPOT_FIELD, view.get().honeypotField());
        assertNoTenantLeak();
    }

    @Test
    void everyReasonAFormIsUnavailableLooksTheSame() throws Exception {
        // feature off
        assertTrue(newService(false).view(TOKEN).isEmpty());
        // malformed token
        assertTrue(service.view("short").isEmpty());
        // unknown token
        when(tenantResolution.resolveTenantSchema(ExternalAccountProvider.WORKFLOW_WEBHOOK, TOKEN_HASH))
            .thenReturn(Optional.empty());
        assertTrue(service.view(TOKEN).isEmpty());
        assertNoTenantLeak();
    }

    @Test
    void aDisabledWebhookOAuthModeInactiveWorkflowOrNonFormNodeAreAllUnavailable() throws Exception {
        // webhook not enabled
        when(triggerNodes.findByWebhookTokenHash(TOKEN_HASH)).thenReturn(
            Optional.of(new WorkflowTriggerNode(WORKFLOW_ID, NODE_ID, WorkflowTriggerChannel.WEBHOOK)));
        assertTrue(service.view(TOKEN).isEmpty());
        assertNoTenantLeak();

        // OAUTH2 mode: the shared-secret style URL stops working
        WorkflowTriggerNode oauth = enabledTriggerNode();
        oauth.setWebhookAuthMode("OAUTH2");
        when(triggerNodes.findByWebhookTokenHash(TOKEN_HASH)).thenReturn(Optional.of(oauth));
        assertTrue(service.view(TOKEN).isEmpty());

        // workflow not active
        when(triggerNodes.findByWebhookTokenHash(TOKEN_HASH)).thenReturn(Optional.of(enabledTriggerNode()));
        when(definitions.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition(WorkflowStatus.DRAFT)));
        assertTrue(service.view(TOKEN).isEmpty());

        // not a form trigger
        when(definitions.findById(WORKFLOW_ID)).thenReturn(Optional.of(definition));
        when(workflowService.resolveRunGraph(definition)).thenReturn(graphWith(Map.of("channel", "WEBHOOK")));
        assertTrue(service.view(TOKEN).isEmpty());

        // unusable schema
        when(workflowService.resolveRunGraph(definition)).thenReturn(graphWith(Map.of("triggerType", "form")));
        assertTrue(service.view(TOKEN).isEmpty());
        assertNoTenantLeak();
    }

    // ---- submit: the happy path -----------------------------------------------------------------------------------

    @Test
    void aValidSubmissionStartsTheRunWithTheValidatedValuesAsInput() throws Exception {
        WorkflowFormService.Submission result = service.submit(TOKEN, validBody(), "203.0.113.9");

        assertEquals(WorkflowFormService.Status.OK, result.status());
        assertEquals("Thanks - we got it.", result.message());
        assertEquals("https://example.com/thanks", result.redirectUrl());

        ArgumentCaptor<String> input = ArgumentCaptor.forClass(String.class);
        verify(triggerPort).triggerAsync(eq(WORKFLOW_ID), eq(NODE_ID), input.capture(), startsWith("workflow-form:"));
        JsonNode sent = new ObjectMapper().readTree(input.getValue());
        assertEquals("Asha", sent.get("name").asText());
        assertTrue(sent.get("agree").asBoolean());
        assertEquals(2, sent.size(), "only schema fields reach the workflow");
        assertNoTenantLeak();
    }

    @Test
    void theRequestedByLabelNeverCarriesMoreThanTheTokenTail() {
        service.submit(TOKEN, validBody(), "203.0.113.9");

        ArgumentCaptor<String> requestedBy = ArgumentCaptor.forClass(String.class);
        verify(triggerPort).triggerAsync(any(UUID.class), anyString(), anyString(), requestedBy.capture());
        assertEquals("workflow-form:" + TOKEN.substring(TOKEN.length() - 4), requestedBy.getValue());
    }

    // ---- submit: validation -----------------------------------------------------------------------------------------

    @Test
    void anInvalidSubmissionIsRejectedPerFieldAndNeverStartsARun() {
        WorkflowFormService.Submission result = service.submit(TOKEN,
            body("values", body("name", "", "agree", false)), "203.0.113.9");

        assertEquals(WorkflowFormService.Status.INVALID, result.status());
        assertEquals("required", result.errors().get("name"));
        assertEquals("required", result.errors().get("agree"));
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
        assertNoTenantLeak();
    }

    @Test
    void aMalformedBodyIsInvalidNotAnError() {
        WorkflowFormService.Submission notAMap = service.submit(TOKEN, body("values", "oops"), "203.0.113.9");
        assertEquals(WorkflowFormService.Status.INVALID, notAMap.status());
        assertEquals("invalid_body", notAMap.errors().get("_form"));

        WorkflowFormService.Submission noBody = service.submit(TOKEN, null, "203.0.113.9");
        assertEquals(WorkflowFormService.Status.INVALID, noBody.status(), "a missing body validates as all-missing");
        assertEquals("required", noBody.errors().get("name"));
    }

    @Test
    void anOversizedPayloadIsRefusedBeforeAnyLookup() {
        WorkflowFormService.Submission result = service.submit(TOKEN,
            body("values", body("name", "x".repeat(5_000))), "203.0.113.9");

        assertEquals(WorkflowFormService.Status.TOO_LARGE, result.status());
        verify(tenantResolution, never()).resolveTenantSchema(any(), anyString());
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
    }

    // ---- submit: honeypot ---------------------------------------------------------------------------------------------

    @Test
    void aFilledHoneypotLooksLikeSuccessButStartsNothingAndLooksNothingUp() {
        Map<String, Object> bot = validBody();
        bot.put(WorkflowFormSchema.HONEYPOT_FIELD, "http://spam.example");

        WorkflowFormService.Submission result = service.submit(TOKEN, bot, "198.51.100.7");

        assertEquals(WorkflowFormService.Status.OK, result.status(), "the bot is told it worked");
        assertNull(result.redirectUrl());
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
        verify(tenantResolution, never()).resolveTenantSchema(any(), anyString());
    }

    // ---- submit: rate limits --------------------------------------------------------------------------------------------

    @Test
    void thePerTokenLimitStopsASubmissionBeforeAnyWork() {
        when(rateLimiter.tryAcquire(eq(TOKEN_PREFIX), anyString(), eq(30L))).thenReturn(false);

        WorkflowFormService.Submission result = service.submit(TOKEN, validBody(), "203.0.113.9");

        assertEquals(WorkflowFormService.Status.RATE_LIMITED, result.status());
        verify(tenantResolution, never()).resolveTenantSchema(any(), anyString());
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
    }

    @Test
    void thePerIpLimitIsIndependentOfTheToken() {
        when(rateLimiter.tryAcquire(eq(IP_PREFIX), anyString(), eq(8L))).thenReturn(false);

        WorkflowFormService.Submission result = service.submit(TOKEN, validBody(), "203.0.113.9");

        assertEquals(WorkflowFormService.Status.RATE_LIMITED, result.status());
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
    }

    @Test
    void theTokenCounterNeverUsesThePlaintextToken() {
        service.submit(TOKEN, validBody(), "203.0.113.9");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryAcquire(eq(TOKEN_PREFIX), key.capture(), eq(30L));
        assertFalse(TOKEN.contains(key.getValue()));
        assertEquals(TOKEN_HASH.substring(0, 24), key.getValue());
    }

    @Test
    void viewsHaveTheirOwnGenerousPerIpLimit() {
        service.allowView("203.0.113.9");

        verify(rateLimiter).tryAcquire(eq("agentic:workflow-form-view-rate:"), anyString(), eq(64L));
    }

    // ---- submit: not found -----------------------------------------------------------------------------------------

    @Test
    void submittingToAnUnknownMalformedOrDisabledFormIsNotFound() {
        assertEquals(WorkflowFormService.Status.NOT_FOUND, service.submit("short", validBody(), "1.2.3.4").status());
        assertEquals(WorkflowFormService.Status.NOT_FOUND, newService(false).submit(TOKEN, validBody(), "1.2.3.4").status());

        when(tenantResolution.resolveTenantSchema(ExternalAccountProvider.WORKFLOW_WEBHOOK, TOKEN_HASH))
            .thenReturn(Optional.empty());
        assertEquals(WorkflowFormService.Status.NOT_FOUND, service.submit(TOKEN, validBody(), "1.2.3.4").status());
        verify(triggerPort, never()).triggerAsync(any(UUID.class), anyString(), anyString(), anyString());
        assertNoTenantLeak();
    }

    // ---- the raw webhook guard --------------------------------------------------------------------------------------

    @Test
    void aFormTriggerNodeIsRecognisedSoItsRawWebhookCanBeRefused() {
        assertTrue(service.isFormTriggerNode(definition, NODE_ID));
        assertFalse(service.isFormTriggerNode(definition, "some-other-node"));
        assertFalse(newService(false).isFormTriggerNode(definition, NODE_ID), "always false while the feature is off");

        when(workflowService.resolveRunGraph(definition)).thenReturn(graphWith(Map.of("channel", "WEBHOOK")));
        assertFalse(service.isFormTriggerNode(definition, NODE_ID));

        when(workflowService.resolveRunGraph(definition)).thenThrow(new RuntimeException("db down"));
        assertFalse(service.isFormTriggerNode(definition, NODE_ID), "never blocks the webhook on an internal error");
    }
}
