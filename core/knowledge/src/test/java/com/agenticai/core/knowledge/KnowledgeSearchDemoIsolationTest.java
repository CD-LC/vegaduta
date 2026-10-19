package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The shared /try demo schema holds every anonymous visitor's studio documents. An UNSCOPED search there must not
 * reach them - otherwise the legacy seeded chat agent (knowledge on, no collection allow-list) could read another
 * visitor's upload.
 */
class KnowledgeSearchDemoIsolationTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static KnowledgeCollection collection(String name) {
        KnowledgeCollection c = mock(KnowledgeCollection.class);
        when(c.getName()).thenReturn(name);
        return c;
    }

    @Test
    void anUnscopedSearchInTheDemoTenantCannotSeeStudioCollections() {
        SidecarClient sidecar = mock(SidecarClient.class);
        KnowledgeCollectionRepository collections = mock(KnowledgeCollectionRepository.class);
        List<KnowledgeCollection> studioCollections = List.of(collection("studio-3f2a"), collection("studio-9b71"));
        when(collections.findAll()).thenReturn(studioCollections);
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.isSharedDemoTenant("demo_shared")).thenReturn(true);

        KnowledgeSearchService service = new KnowledgeSearchService(
            sidecar, mock(org.springframework.jdbc.core.JdbcTemplate.class), collections, mock(KnowledgeDocumentRepository.class), false);
        service.setTenantLimits(limits);
        TenantContext.set(TenantContext.of("demo_shared", "demo:someone", Set.of()));

        // Every collection in the schema is a studio one, so nothing is left to search: no embedding call, no rows.
        assertTrue(service.search("what is the refund policy", 3, null).isEmpty());
        assertTrue(service.search("what is the refund policy", 3, Set.of()).isEmpty());
        verifyNoInteractions(sidecar);
    }
}
