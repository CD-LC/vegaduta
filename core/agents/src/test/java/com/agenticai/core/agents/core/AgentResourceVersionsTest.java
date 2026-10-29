package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentResourceVersions} backs conditional requests on {@code /api/agents/{id}}: the entity
 * tag is the agent's {@code updatedAt}. These pin the collection path and that an unknown id yields
 * an empty answer rather than an error.
 */
class AgentResourceVersionsTest {

    @Test
    void theCollectionPathIsTheAgentsApi() {
        AgentResourceVersions versions = new AgentResourceVersions(mock(AgentRepository.class));
        assertEquals("/api/agents", versions.collectionPath());
    }

    @Test
    void lastModifiedIsTheAgentsUpdatedAt() {
        Agent agent =
                new Agent(
                        "n",
                        "p",
                        ProviderName.ANTHROPIC,
                        "m",
                        1024,
                        0.7,
                        1.0,
                        true,
                        null,
                        ReasoningDepth.FAST,
                        TuningMode.MANUAL);
        AgentRepository repository = mock(AgentRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(agent));

        AgentResourceVersions versions = new AgentResourceVersions(repository);

        assertEquals(Optional.of(agent.getUpdatedAt()), versions.lastModified(id));
    }

    @Test
    void anUnknownIdHasNoLastModified() {
        AgentRepository repository = mock(AgentRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        AgentResourceVersions versions = new AgentResourceVersions(repository);

        assertTrue(versions.lastModified(id).isEmpty());
    }
}
