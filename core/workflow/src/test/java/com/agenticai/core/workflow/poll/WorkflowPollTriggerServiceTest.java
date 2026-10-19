package com.agenticai.core.workflow.poll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.net.SsrfGuard;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.WorkflowValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class WorkflowPollTriggerServiceTest {

    private WorkflowPollTriggerRepository repository;
    private WorkflowPollFetcher fetcher;
    private EncryptionService encryptionService;
    private WorkflowService workflowService;
    private WorkflowPollTriggerService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String NODE_ID = "trigger-1";

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowPollTriggerRepository.class);
        fetcher = mock(WorkflowPollFetcher.class);
        encryptionService = mock(EncryptionService.class);
        workflowService = mock(WorkflowService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(workflowService);
        // A guard whose resolver answers a public address for every name, so
        // no real DNS happens in tests.
        SsrfGuard guard = SsrfGuard.strict().withResolver(host -> {
            try {
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            } catch (Exception e) {
                throw new java.net.UnknownHostException(host);
            }
        });
        service = new WorkflowPollTriggerService(
            repository, fetcher, new WorkflowPollProperties(), encryptionService,
            new ObjectMapper(), provider, guard);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowPollTrigger savedTrigger() {
        WorkflowPollTrigger trigger = new WorkflowPollTrigger(WORKFLOW_ID, NODE_ID);
        trigger.configure("https://api.example.com/items", "GET", null, 300, "items", "id", true);
        return trigger;
    }

    private WorkflowPollTriggerService.ConfigureRequest request(String url) {
        return new WorkflowPollTriggerService.ConfigureRequest(
            url, "GET", null, 300, "items", "id", true);
    }

    @Test
    void configureValidatesAndPersists() {
        when(repository.findByNodeId(NODE_ID)).thenReturn(Optional.empty());
        WorkflowPollTrigger saved = service.configure(WORKFLOW_ID, NODE_ID, request("https://api.example.com/items"));
        assertEquals(NODE_ID, saved.getNodeId());
        assertEquals("https://api.example.com/items", saved.getUrl());
        assertTrue(saved.isEnabled());
        verify(repository).save(any(WorkflowPollTrigger.class));
    }

    @Test
    void configureRejectsInternalUrls() {
        WorkflowValidationException ex = assertThrows(WorkflowValidationException.class,
            () -> service.configure(WORKFLOW_ID, NODE_ID, request("http://localhost:8080/admin")));
        assertTrue(ex.getMessage().contains("Poll URL refused"));
    }

    @Test
    void configureRejectsBadIntervalAndMethod() {
        assertThrows(WorkflowValidationException.class, () -> service.configure(WORKFLOW_ID, NODE_ID,
            new WorkflowPollTriggerService.ConfigureRequest(
                "https://api.example.com/x", "DELETE", null, 300, "", "id", true)));
        assertThrows(WorkflowValidationException.class, () -> service.configure(WORKFLOW_ID, NODE_ID,
            new WorkflowPollTriggerService.ConfigureRequest(
                "https://api.example.com/x", "GET", null, 5, "", "id", true)));
    }

    @Test
    void firstPollSeedsWithoutFiring() {
        WorkflowPollTrigger trigger = savedTrigger();
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(fetcher.fetch(anyString(), eq("GET"), any()))
            .thenReturn("{\"items\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");

        service.runDue(trigger.getId());

        verify(workflowService, never()).run(any(), anyString(), anyString(), anyString());
        assertEquals(WorkflowPollTrigger.STATUS_SEEDED, trigger.getLastStatus());
        assertNotNull(trigger.getStateJson());
        assertTrue(trigger.getStateJson().contains("\"a\""));
    }

    private WorkflowPollTrigger seededTrigger(String stateJson) {
        WorkflowPollTrigger trigger = savedTrigger();
        trigger.markPolled(WorkflowPollTrigger.STATUS_SEEDED, stateJson, 0, 8);
        return trigger;
    }

    @Test
    void subsequentPollFiresOnlyNewItems() {
        // Pretend a previous poll already seeded "a" and "b".
        WorkflowPollTrigger trigger = seededTrigger("{\"seeded\":true,\"seenIds\":[\"a\",\"b\"]}");
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(fetcher.fetch(anyString(), eq("GET"), any()))
            .thenReturn("{\"items\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\",\"v\":3}]}");

        service.runDue(trigger.getId());

        // Only the new item "c" fires a run.
        verify(workflowService, times(1))
            .run(eq(WORKFLOW_ID), eq(NODE_ID), Mockito.contains("\"c\"") , anyString());
        assertEquals(WorkflowPollTrigger.STATUS_OK, trigger.getLastStatus());
        assertEquals(0, trigger.getConsecutiveFailures());
    }

    @Test
    void fetchFailureRecordsErrorAndBacksOff() {
        WorkflowPollTrigger trigger = savedTrigger();
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(fetcher.fetch(anyString(), anyString(), any()))
            .thenThrow(new PollFetchException("endpoint answered HTTP 500"));

        service.runDue(trigger.getId());

        verify(workflowService, never()).run(any(), anyString(), anyString(), anyString());
        assertEquals(WorkflowPollTrigger.STATUS_ERROR, trigger.getLastStatus());
        assertEquals(1, trigger.getConsecutiveFailures());
        // Backoff: next poll is interval * (failures+1) out, not one interval.
        assertNotNull(trigger.getNextPollAt());
    }

    @Test
    void disabledTriggerDoesNotPoll() {
        WorkflowPollTrigger trigger = savedTrigger();
        trigger.disable();
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));

        service.runDue(trigger.getId());

        Mockito.verifyNoInteractions(fetcher);
    }

    @Test
    void failedRunLeavesItemUnseenForRetry() {
        WorkflowPollTrigger trigger = seededTrigger("{\"seeded\":true,\"seenIds\":[]}");
        when(repository.findById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(fetcher.fetch(anyString(), eq("GET"), any()))
            .thenReturn("{\"items\":[{\"id\":\"x\"}]}");
        when(workflowService.run(any(), anyString(), anyString(), anyString()))
            .thenThrow(new RuntimeException("run failed"));

        service.runDue(trigger.getId());

        assertEquals(WorkflowPollTrigger.STATUS_OK, trigger.getLastStatus());
        // The failed item's id was NOT claimed - it retries next poll.
        assertTrue(trigger.getStateJson().contains("\"seenIds\":[]"));
    }

    @Test
    void headersAreEncryptedAtRest() {
        when(repository.findByNodeId(NODE_ID)).thenReturn(Optional.empty());
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        service.configure(WORKFLOW_ID, NODE_ID,
            new WorkflowPollTriggerService.ConfigureRequest(
                "https://api.example.com/x", "GET", Map.of("Authorization", "Bearer t"), 300, "", "id", true));
        verify(encryptionService).encrypt(Mockito.contains("Bearer t"));
    }
}
