package com.agenticai.core.omnichannel.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link WorkflowTriggerNodeService} is the "dumb" node-to-channel-binding
 * CRUD every inbound channel controller (Twilio WhatsApp/SMS, Discord,
 * Slack, ...) checks before falling back to default-agent-chat, plus the
 * rate-limit delegate that answers the direct feedback quoted in its own
 * javadoc ("every message might trigger workflow isnt it?"). Ported from
 * {@code ChannelWorkflowTriggerServiceTest} (channel-to-workflow-id shape)
 * to this class's channel-to-TRIGGER-NODE shape - every case below is the
 * same behavior, just upserting/looking up by {@code nodeId} instead of by
 * {@code channel} and resolving a {@link WorkflowTriggerNodeService.TriggerBinding}
 * instead of a bare workflow id.
 *
 * <p>{@link WorkflowTriggerNode} is constructed for real via its public
 * constructor rather than mocked - none of the fields needed by this service
 * are JPA-generated-only, so there's no reason to mock it per this
 * codebase's "mock only when there's no clean way to set a needed field"
 * convention.
 */
class WorkflowTriggerNodeServiceTest {

    private WorkflowTriggerNodeRepository repository;
    private WorkflowTriggerRateLimiter rateLimiter;
    private WorkflowTriggerNodeService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowTriggerNodeRepository.class);
        rateLimiter = mock(WorkflowTriggerRateLimiter.class);
        service = new WorkflowTriggerNodeService(repository, rateLimiter);
    }

    // --- bindingFor() ------------------------------------------------------

    @Test
    void bindingForReturnsTheBoundWorkflowAndNodeIdWhenOneExists() {
        UUID workflowId = UUID.randomUUID();
        when(repository.findByChannel(WorkflowTriggerChannel.WHATSAPP))
            .thenReturn(Optional.of(new WorkflowTriggerNode(workflowId, "node-1", WorkflowTriggerChannel.WHATSAPP)));

        Optional<WorkflowTriggerNodeService.TriggerBinding> found = service.bindingFor(WorkflowTriggerChannel.WHATSAPP);

        assertTrue(found.isPresent());
        assertEquals(workflowId, found.get().workflowId());
        assertEquals("node-1", found.get().nodeId());
    }

    @Test
    void bindingForReturnsEmptyWhenNoWorkflowIsBoundToTheChannel() {
        when(repository.findByChannel(WorkflowTriggerChannel.SMS)).thenReturn(Optional.empty());

        assertTrue(service.bindingFor(WorkflowTriggerChannel.SMS).isEmpty());
    }

    // --- tryAcquireTriggerSlot(): pure delegate to the rate limiter --------

    @Test
    void tryAcquireTriggerSlotDelegatesDirectlyToTheRateLimiterAndReturnsTrue() {
        when(rateLimiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "+15551234567")).thenReturn(true);

        assertTrue(service.tryAcquireTriggerSlot(WorkflowTriggerChannel.WHATSAPP, "+15551234567"));
    }

    @Test
    void tryAcquireTriggerSlotDelegatesDirectlyToTheRateLimiterAndReturnsFalse() {
        when(rateLimiter.tryAcquire(WorkflowTriggerChannel.WHATSAPP, "+15551234567")).thenReturn(false);

        assertFalse(service.tryAcquireTriggerSlot(WorkflowTriggerChannel.WHATSAPP, "+15551234567"));
    }

    // --- getTrigger() --------------------------------------------------------

    @Test
    void getTriggerReturnsTheRepositoryEntryUnmodified() {
        WorkflowTriggerNode entry = new WorkflowTriggerNode(UUID.randomUUID(), "node-call", WorkflowTriggerChannel.CALL);
        when(repository.findByChannel(WorkflowTriggerChannel.CALL)).thenReturn(Optional.of(entry));

        assertEquals(Optional.of(entry), service.getTrigger(WorkflowTriggerChannel.CALL));
    }

    // --- setTrigger(): upsert-by-nodeId ---------------------------------------

    @Test
    void setTriggerCreatesABrandNewEntryWhenNoneExistsForTheNode() {
        UUID workflowId = UUID.randomUUID();
        when(repository.findByNodeId("node-1")).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowTriggerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setTrigger(workflowId, "node-1", WorkflowTriggerChannel.WHATSAPP);

        org.mockito.ArgumentCaptor<WorkflowTriggerNode> captor = org.mockito.ArgumentCaptor.forClass(WorkflowTriggerNode.class);
        verify(repository).save(captor.capture());
        assertEquals(workflowId, captor.getValue().getWorkflowId());
        assertEquals("node-1", captor.getValue().getNodeId());
        assertEquals(WorkflowTriggerChannel.WHATSAPP, captor.getValue().getChannel());
    }

    @Test
    void setTriggerRebindsAnExistingNodeToANewWorkflowRatherThanCreatingASecondRow() {
        UUID oldWorkflowId = UUID.randomUUID();
        UUID newWorkflowId = UUID.randomUUID();
        WorkflowTriggerNode existing = new WorkflowTriggerNode(oldWorkflowId, "node-1", WorkflowTriggerChannel.WHATSAPP);
        when(repository.findByNodeId("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkflowTriggerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setTrigger(newWorkflowId, "node-1", WorkflowTriggerChannel.WHATSAPP);

        assertEquals(newWorkflowId, existing.getWorkflowId());
        verify(repository).save(existing);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(t -> t != existing));
    }

    @Test
    void setTriggerIsIdempotentWhenCalledAgainWithTheSameWorkflowAndChannel() {
        UUID workflowId = UUID.randomUUID();
        WorkflowTriggerNode existing = new WorkflowTriggerNode(workflowId, "node-1", WorkflowTriggerChannel.WHATSAPP);
        when(repository.findByNodeId("node-1")).thenReturn(Optional.of(existing));
        when(repository.save(any(WorkflowTriggerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setTrigger(workflowId, "node-1", WorkflowTriggerChannel.WHATSAPP);

        assertEquals(workflowId, existing.getWorkflowId());
        verify(repository, times(1)).save(existing);
    }

    @Test
    void setTriggerThrowsIllegalStateExceptionWhenTheChannelIsAlreadyBoundToAnotherNode() {
        UUID workflowId = UUID.randomUUID();
        when(repository.findByNodeId("node-2")).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowTriggerNode.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
            .when(repository).flush();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.setTrigger(workflowId, "node-2", WorkflowTriggerChannel.WHATSAPP));
    }

    // --- clearTrigger() --------------------------------------------------------

    @Test
    void clearTriggerDeletesTheExistingEntryForTheNode() {
        WorkflowTriggerNode existing = new WorkflowTriggerNode(UUID.randomUUID(), "node-sms", WorkflowTriggerChannel.SMS);
        when(repository.findByNodeId("node-sms")).thenReturn(Optional.of(existing));

        service.clearTrigger("node-sms");

        verify(repository).delete(existing);
    }

    @Test
    void clearTriggerDoesNothingWhenNoEntryExistsForTheNode() {
        when(repository.findByNodeId("node-sms")).thenReturn(Optional.empty());

        service.clearTrigger("node-sms");

        verify(repository, never()).delete(any());
    }

    // --- enableWebhook() / disableWebhook() -------------------------------------

    @Test
    void enableWebhookCreatesANewWebhookBoundEntryWhenNoneExistsForTheNode() {
        UUID workflowId = UUID.randomUUID();
        when(repository.findByNodeId("node-wh")).thenReturn(Optional.empty());
        when(repository.save(any(WorkflowTriggerNode.class))).thenAnswer(inv -> inv.getArgument(0));

        service.enableWebhook(workflowId, "node-wh", "encrypted-token", "token-hash");

        org.mockito.ArgumentCaptor<WorkflowTriggerNode> captor = org.mockito.ArgumentCaptor.forClass(WorkflowTriggerNode.class);
        verify(repository).save(captor.capture());
        assertEquals(WorkflowTriggerChannel.WEBHOOK, captor.getValue().getChannel());
        assertEquals("encrypted-token", captor.getValue().getWebhookTokenEncrypted());
        assertEquals("token-hash", captor.getValue().getWebhookTokenHash());
        assertTrue(captor.getValue().isWebhookEnabled());
    }

    @Test
    void disableWebhookKeepsTheTokenButFlipsEnabledToFalse() {
        WorkflowTriggerNode existing = new WorkflowTriggerNode(UUID.randomUUID(), "node-wh", WorkflowTriggerChannel.WEBHOOK);
        existing.enableWebhook("encrypted-token", "token-hash");
        when(repository.findByNodeId("node-wh")).thenReturn(Optional.of(existing));

        service.disableWebhook("node-wh");

        assertFalse(existing.isWebhookEnabled());
        assertEquals("encrypted-token", existing.getWebhookTokenEncrypted());
        verify(repository).save(existing);
    }

    @Test
    void disableWebhookDoesNothingWhenNoEntryExistsForTheNode() {
        when(repository.findByNodeId("node-wh")).thenReturn(Optional.empty());

        service.disableWebhook("node-wh");

        verify(repository, never()).save(any());
    }

    // --- findByWebhookTokenHash() ---------------------------------------------

    @Test
    void findByWebhookTokenHashDelegatesToRepository() {
        WorkflowTriggerNode existing = new WorkflowTriggerNode(UUID.randomUUID(), "node-wh", WorkflowTriggerChannel.WEBHOOK);
        when(repository.findByWebhookTokenHash("token-hash")).thenReturn(Optional.of(existing));

        assertEquals(Optional.of(existing), service.findByWebhookTokenHash("token-hash"));
    }

    // --- new #422 channels (DISCORD/EMAIL) - this service is channel-generic,
    // so these confirm DiscordWebhookController/EmailInboundPollingJob's new
    // bindingFor()/setTrigger() calls work the same way WHATSAPP/SMS already
    // do above, not new behavior of the service itself. --------------------

    @Test
    void bindingForReturnsTheBoundWorkflowForDiscord() {
        UUID workflowId = UUID.randomUUID();
        when(repository.findByChannel(WorkflowTriggerChannel.DISCORD))
            .thenReturn(Optional.of(new WorkflowTriggerNode(workflowId, "node-discord", WorkflowTriggerChannel.DISCORD)));

        Optional<WorkflowTriggerNodeService.TriggerBinding> found = service.bindingFor(WorkflowTriggerChannel.DISCORD);

        assertTrue(found.isPresent());
        assertEquals(workflowId, found.get().workflowId());
        assertEquals("node-discord", found.get().nodeId());
    }

    @Test
    void bindingForReturnsEmptyWhenNoWorkflowIsBoundToEmail() {
        when(repository.findByChannel(WorkflowTriggerChannel.EMAIL)).thenReturn(Optional.empty());

        assertTrue(service.bindingFor(WorkflowTriggerChannel.EMAIL).isEmpty());
    }

    @Test
    void clearTriggerDeletesTheExistingEntryForDiscord() {
        WorkflowTriggerNode existing = new WorkflowTriggerNode(UUID.randomUUID(), "node-discord", WorkflowTriggerChannel.DISCORD);
        when(repository.findByNodeId("node-discord")).thenReturn(Optional.of(existing));

        service.clearTrigger("node-discord");

        verify(repository).delete(existing);
    }
}
