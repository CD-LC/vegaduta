package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.crm.Lead;
import com.agenticai.core.omnichannel.crm.LeadActivityType;
import com.agenticai.core.omnichannel.crm.LeadService;
import com.agenticai.core.omnichannel.crm.LeadStage;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class WorkflowCrmLeadNodeHandlerTest {

    private LeadService service;
    private WorkflowCrmLeadNodeHandler handler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = mock(LeadService.class);
        ObjectProvider<LeadService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        handler = new WorkflowCrmLeadNodeHandler(provider, new ObjectMapper());
    }

    private static WorkflowNodeDto node(Map<String, Object> config) {
        return new WorkflowNodeDto("n1", "crm_lead", null, Map.of("label", "CRM", "config", config));
    }

    private static Lead lead(String email, LeadStage stage) {
        Lead l = new Lead("Ann", email);
        l.setStage(stage);
        ReflectionTestUtils.setField(l, "id", UUID.randomUUID());
        return l;
    }

    @Test
    void upsertRendersTemplatesFromJsonInput() {
        when(service.upsert(any())).thenReturn(lead("ann@x.com", LeadStage.NEW));
        NodeExecutionResult r = handler.execute(
            node(Map.of("action", "upsert", "email", "{{email}}", "name", "{{name}}", "source", "workflow", "stage", "contacted")),
            "{\"email\":\"ann@x.com\",\"name\":\"Ann\"}");
        ArgumentCaptor<LeadService.LeadInput> in = ArgumentCaptor.forClass(LeadService.LeadInput.class);
        verify(service).upsert(in.capture());
        assertEquals("ann@x.com", in.getValue().email());
        assertEquals("Ann", in.getValue().name());
        assertEquals(LeadStage.CONTACTED, in.getValue().stage());
        assertEquals("OK", r.status());
        assertTrue(r.output().contains("\"stage\":\"NEW\""));
    }

    @Test
    void stageActionFindsLeadByEmail() {
        Lead l = lead("ann@x.com", LeadStage.NEW);
        when(service.findByEmail("ann@x.com")).thenReturn(Optional.of(l));
        when(service.changeStage(l.getId(), LeadStage.QUALIFIED, null)).thenReturn(l);
        NodeExecutionResult r = handler.execute(
            node(Map.of("action", "stage", "email", "{{input}}", "stage", "QUALIFIED")), "ann@x.com");
        assertEquals("OK", r.status());
        verify(service).changeStage(l.getId(), LeadStage.QUALIFIED, null);
    }

    @Test
    void stageActionWithUnknownLeadIsSkippedNotFailed() {
        when(service.findByEmail("nobody@x.com")).thenReturn(Optional.empty());
        NodeExecutionResult r = handler.execute(
            node(Map.of("action", "stage", "email", "nobody@x.com", "stage", "WON")), "in");
        assertEquals("SKIPPED", r.status());
        assertEquals("in", r.output());
        verify(service, never()).changeStage(any(), any(), any());
    }

    @Test
    void activityActionDefaultsToNoteWithInputAsBody() {
        Lead l = lead("ann@x.com", LeadStage.NEW);
        when(service.findByEmail("ann@x.com")).thenReturn(Optional.of(l));
        when(service.get(l.getId())).thenReturn(l);
        handler.execute(node(Map.of("action", "activity", "email", "ann@x.com")), "called back");
        verify(service).addActivity(any(), org.mockito.ArgumentMatchers.eq(LeadActivityType.NOTE),
            org.mockito.ArgumentMatchers.eq("called back"), any());
    }

    @Test
    void invalidStageIsSkippedWithMessage() {
        NodeExecutionResult r = handler.execute(node(Map.of("action", "upsert", "email", "a@x.com", "stage", "bogus")), "in");
        assertEquals("SKIPPED", r.status());
        assertTrue(r.summary().contains("failed"));
    }

    @Test
    void unavailableServicePassesInputThrough() {
        @SuppressWarnings("unchecked")
        ObjectProvider<LeadService> none = mock(ObjectProvider.class);
        NodeExecutionResult r = new WorkflowCrmLeadNodeHandler(none, new ObjectMapper()).execute(node(Map.of()), "x");
        assertEquals("SKIPPED", r.status());
        assertEquals("x", r.output());
    }
}
