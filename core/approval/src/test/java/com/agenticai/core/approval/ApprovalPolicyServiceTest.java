package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Fast, Spring-free coverage for {@link ApprovalPolicyService}: the
 * override-beats-default resolution order of {@code requiresApproval}, and
 * {@code setOverride}'s delegation to the repository's atomic native upsert
 * (which replaced the read-then-write flow whose documented insert race this
 * change closed - the service must no longer do a pre-read at all).
 */
class ApprovalPolicyServiceTest {

    private ApprovalPolicyOverrideRepository repository;
    private ApprovalPolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApprovalPolicyOverrideRepository.class);
        service = new ApprovalPolicyService(repository);
    }

    @Test
    void defaultSensitivePatternGatesWithoutAnyOverride() {
        when(repository.findByActionType("channel.whatsapp.send")).thenReturn(Optional.empty());
        assertTrue(service.requiresApproval("channel.whatsapp.send"));
    }

    @Test
    void nonSensitiveActionDefaultsToNoApproval() {
        when(repository.findByActionType("agent.config.read")).thenReturn(Optional.empty());
        assertFalse(service.requiresApproval("agent.config.read"));
    }

    @Test
    void explicitOverrideWinsInBothDirections() {
        when(repository.findByActionType("channel.whatsapp.send"))
            .thenReturn(Optional.of(new ApprovalPolicyOverride("channel.whatsapp.send", false)));
        assertFalse(service.requiresApproval("channel.whatsapp.send"));

        when(repository.findByActionType("agent.config.read"))
            .thenReturn(Optional.of(new ApprovalPolicyOverride("agent.config.read", true)));
        assertTrue(service.requiresApproval("agent.config.read"));
    }

    @Test
    void setOverrideDelegatesToAtomicUpsertWithoutPreRead() {
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Instant> updatedAt = ArgumentCaptor.forClass(Instant.class);

        service.setOverride("campaign.bulk_send", true);

        verify(repository).upsert(id.capture(), eq("campaign.bulk_send"), eq(true), updatedAt.capture());
        assertNotNull(id.getValue());
        assertNotNull(updatedAt.getValue());
        // The old read-then-write implementation is what raced; the fixed
        // path must not consult findByActionType at all.
        verify(repository, never()).findByActionType(any());
        verify(repository, never()).save(any());
    }
}
