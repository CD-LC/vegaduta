package com.agenticai.core.agents.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SpecialistRegistry} with its repository mocked (no DB, no Spring context) - the
 * capability-tag normalization contract every lookup in this feature relies on (so "Billing",
 * " billing", "billing " all address the same specialist(s) regardless of how each caller typed
 * it), and that re-registering an existing (agent, tag) pair updates rather than duplicates.
 */
class SpecialistRegistryTest {

    private AgentSpecializationRepository repository;
    private SpecialistRegistry registry;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AgentSpecializationRepository.class);
        registry = new SpecialistRegistry(repository);
        when(repository.save(any(AgentSpecialization.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerNormalizesTheCapabilityTagBeforeWriting() {
        when(repository.findByAgentIdAndCapabilityTag(agentId, "billing")).thenReturn(Optional.empty());

        AgentSpecialization saved = registry.register(agentId, "  Billing  ", "handles refunds");

        assertEquals("billing", saved.getCapabilityTag());
    }

    @Test
    void registerRejectsABlankCapabilityTag() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(agentId, "   ", "desc"));
        verify(repository, never()).save(any());
    }

    @Test
    void reRegisteringAnExistingTagUpdatesTheDescriptionInsteadOfInsertingADuplicate() {
        AgentSpecialization existing = new AgentSpecialization(agentId, "billing", "old description");
        when(repository.findByAgentIdAndCapabilityTag(agentId, "billing")).thenReturn(Optional.of(existing));

        AgentSpecialization result = registry.register(agentId, "billing", "new description");

        assertEquals("new description", result.getDescription());
        verify(repository).save(existing);
    }

    @Test
    void findByCapabilityNormalizesTheLookupTagTheSameWay() {
        registry.findByCapability(" Billing");

        verify(repository).findByCapabilityTagOrderByCreatedAtAsc(eq("billing"));
    }

    @Test
    void deregisterNormalizesTheTagTheSameWay() {
        registry.deregister(agentId, "BILLING");

        verify(repository).deleteByAgentIdAndCapabilityTag(agentId, "billing");
    }
}
