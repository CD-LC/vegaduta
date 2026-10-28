package com.agenticai.core.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.knowledge.KnowledgeAccessService.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The unattended-run seam of the {@code search_knowledge_base} tool ({@code asOwner}). */
class KnowledgeSearchToolCallbackOwnerTest {

    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final UUID collection = UUID.randomUUID();

    @Test
    void anInteractiveToolSearchesAsTheInvokingUser() {
        when(search.search(anyString(), anyInt(), any(), anyBoolean())).thenReturn(List.of());

        new KnowledgeSearchToolCallback(search, Set.of(collection)).call("{\"query\":\"q\"}");

        verify(search).search("q", 3, Set.of(collection), false);
        verify(search, never()).searchAs(anyString(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    void anUnattendedToolSearchesAsTheAgentOwnerKeepingTheAllowList() {
        when(search.searchAs(anyString(), anyInt(), any(), anyBoolean(), any())).thenReturn(List.of());

        new KnowledgeSearchToolCallback(search, Set.of(collection)).asOwner("owner-sub").call("{\"query\":\"q\"}");

        verify(search).searchAs("q", 3, Set.of(collection), false, Principal.ofOwner("owner-sub"));
        verify(search, never()).search(anyString(), anyInt(), any(), anyBoolean());
    }

    @Test
    void aBlankOwnerLeavesTheInvokingCallerInCharge() {
        when(search.search(anyString(), anyInt(), any(), anyBoolean())).thenReturn(List.of());

        new KnowledgeSearchToolCallback(search, Set.of()).asOwner(" ").call("{\"query\":\"q\"}");

        verify(search).search("q", 3, Set.of(), false);
    }

    @Test
    void theOwnerBasisKeepsTheConfiguredTopK() {
        when(search.searchAs(anyString(), anyInt(), any(), anyBoolean(), any())).thenReturn(List.of());

        new KnowledgeSearchToolCallback(search, Set.of(), 9, 0.0).asOwner("owner-sub").call("{\"query\":\"q\"}");

        verify(search).searchAs("q", 9, Set.of(), false, Principal.ofOwner("owner-sub"));
    }
}
