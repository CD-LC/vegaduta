package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.dto.AgentKnowledgeAccessUpdateRequest;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PATCH /{id}/knowledge-access must 404 on an unknown collection id instead of hitting the FK
 * (500).
 */
class AgentControllerKnowledgeAccessTest {

    private AgentService agentService;
    private KnowledgeCollectionService collectionService;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        collectionService = mock(KnowledgeCollectionService.class);
        controller = new AgentController(agentService, null, collectionService);
    }

    @Test
    void unknownCollectionIdIsRejectedBeforeAnythingIsPersisted() {
        UUID agentId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        doThrow(new KnowledgeCollectionNotFoundException(missing))
                .when(collectionService)
                .requireExists(missing);

        assertThrows(
                KnowledgeCollectionNotFoundException.class,
                () ->
                        controller.setKnowledgeAccess(
                                agentId, new AgentKnowledgeAccessUpdateRequest(Set.of(missing))));
        verify(agentService, never()).setAllowedKnowledgeCollectionIds(any(), any());
    }

    @Test
    void knownIdsAreDelegatedAndEmptyOrNullClearsWithoutLookups() {
        UUID agentId = UUID.randomUUID();
        UUID known = UUID.randomUUID();
        AgentResponse resp = mock(AgentResponse.class);
        when(agentService.setAllowedKnowledgeCollectionIds(any(), any())).thenReturn(resp);

        assertSame(
                resp,
                controller.setKnowledgeAccess(
                        agentId, new AgentKnowledgeAccessUpdateRequest(Set.of(known))));
        verify(collectionService).requireExists(known);
        verify(agentService).setAllowedKnowledgeCollectionIds(agentId, Set.of(known));

        controller.setKnowledgeAccess(agentId, new AgentKnowledgeAccessUpdateRequest(null));
        controller.setKnowledgeAccess(agentId, new AgentKnowledgeAccessUpdateRequest(Set.of()));
        verify(collectionService).requireExists(any());
    }
}
