package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Central enforcement in {@link KnowledgeSearchService}: whatever reaches it (REST search, the
 * agent's {@code search_knowledge_base} tool, a workflow knowledge node, the MCP tool) is narrowed
 * to the collections the caller may read. Each collection uses a different embedding model, so
 * {@code sidecar.embedQuery(query, model)} reveals exactly which collections were actually searched.
 */
class KnowledgeSearchAccessTest {

    private static final String OWNER = "owner-sub";
    private static final String MEMBER = "member-sub";
    private static final String PRIVATE_MODEL = "private-model";
    private static final String SHARED_MODEL = "shared-model";
    private static final String TENANT_MODEL = "tenant-model";

    private SidecarClient sidecar;
    private KnowledgeCollectionRepository collections;
    private ResourceAccessService sharing;
    private KnowledgeSearchService search;
    private KnowledgeCollection privateCollection;
    private KnowledgeCollection sharedCollection;
    private KnowledgeCollection tenantCollection;

    @BeforeEach
    void setUp() {
        sidecar = mock(SidecarClient.class);
        collections = mock(KnowledgeCollectionRepository.class);
        sharing = mock(ResourceAccessService.class);
        when(sharing.activeGrantLevels(eq(ResourceType.KNOWLEDGE_COLLECTION), any())).thenReturn(Map.of());
        when(sidecar.embedQuery(anyString(), anyString())).thenReturn(new float[] {0.1f});

        KnowledgeAccessService access = new KnowledgeAccessService(
            collections, mock(KnowledgeDocumentRepository.class), sharing, mock(AuditLogService.class));
        search = new KnowledgeSearchService(
            sidecar, mock(JdbcTemplate.class), collections, mock(KnowledgeDocumentRepository.class), false);
        search.setAccess(access);

        privateCollection = collection(OWNER, KnowledgeVisibility.PRIVATE, PRIVATE_MODEL);
        sharedCollection = collection(OWNER, KnowledgeVisibility.SHARED, SHARED_MODEL);
        tenantCollection = collection(null, null, TENANT_MODEL); // a pre-V710 collection
        List<KnowledgeCollection> all = List.of(privateCollection, sharedCollection, tenantCollection);
        when(collections.findAll()).thenReturn(all);
        when(collections.findAllById(any())).thenAnswer(inv -> {
            Set<UUID> ids = Set.copyOf((java.util.Collection<UUID>) inv.getArgument(0));
            return all.stream().filter(c -> ids.contains(c.getId())).toList();
        });
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static KnowledgeCollection collection(String owner, KnowledgeVisibility visibility, String model) {
        KnowledgeCollection c = new KnowledgeCollection("c-" + model, "d", null, model, "simple", owner, visibility);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private void callAs(String subject, String... roles) {
        TenantContext.set(TenantContext.of("tenant-1", subject, Set.of(roles)));
    }

    private void searched(String... models) {
        for (String model : List.of(PRIVATE_MODEL, SHARED_MODEL, TENANT_MODEL)) {
            if (List.of(models).contains(model)) {
                verify(sidecar).embedQuery("q", model);
            } else {
                verify(sidecar, never()).embedQuery("q", model);
            }
        }
    }

    @Test
    void anUnscopedSearchByAMemberWithoutGrantsOnlyReachesTheTenantVisibleCollection() {
        callAs(MEMBER);

        search.search("q", 5, Set.of());

        searched(TENANT_MODEL);
    }

    @Test
    void theDefaultExistingCollectionStaysSearchableByAnyMemberWithNoGrantAtAll() {
        callAs(MEMBER);

        search.search("q", 5, Set.of(tenantCollection.getId()));

        searched(TENANT_MODEL);
    }

    @Test
    void aMemberWhoNamesAPrivateCollectionGetsNothingAndNeverTriggersAnEmbedding() {
        callAs(MEMBER);

        assertTrue(search.search("q", 5, Set.of(privateCollection.getId())).isEmpty());

        searched();
    }

    @Test
    void theOwnerSearchesTheirOwnPrivateAndSharedCollections() {
        callAs(OWNER);

        search.search("q", 5, Set.of());

        searched(PRIVATE_MODEL, SHARED_MODEL, TENANT_MODEL);
    }

    @Test
    void aViewerGrantOpensExactlyTheSharedCollectionItNames() {
        callAs(MEMBER);
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, MEMBER))
            .thenReturn(Map.of(sharedCollection.getId(), PermissionLevel.VIEWER,
                privateCollection.getId(), PermissionLevel.VIEWER));

        search.search("q", 5, Set.of());

        searched(SHARED_MODEL, TENANT_MODEL); // the grant on the PRIVATE one is dormant
    }

    @Test
    void anAgentTurnSearchesItsAllowListIntersectedWithTheInvokingUsersAccess() {
        // The agent's builder allow-listed the private and the tenant collection; the invoking
        // member can only read the tenant one, so only that one is searched.
        callAs(MEMBER);

        search.search("q", 5, Set.of(privateCollection.getId(), tenantCollection.getId()));
        searched(TENANT_MODEL);
    }

    @Test
    void theSameAgentTurnReachesBothWhenTheInvokingUserIsTheOwner() {
        callAs(OWNER);

        search.search("q", 5, Set.of(privateCollection.getId(), tenantCollection.getId()));

        searched(PRIVATE_MODEL, TENANT_MODEL);
    }

    @Test
    void anUnattendedRunWithoutAnOwnerBasisSeesTenantCollectionsOnly() {
        callAs("system:agent-share:abc"); // synthetic subject: owns nothing, holds no grants

        search.search("q", 5, Set.of(privateCollection.getId(), tenantCollection.getId()));

        searched(TENANT_MODEL);
    }

    @Test
    void anUnattendedRunActingAsTheAgentOwnerGetsTheOwnersAccessButNeverAdminOverride() {
        callAs("system:schedule:nightly");

        search.searchAs("q", 5, Set.of(privateCollection.getId(), tenantCollection.getId()), false,
            KnowledgeAccessService.Principal.ofOwner(OWNER));

        searched(PRIVATE_MODEL, TENANT_MODEL);
    }

    @Test
    void anOwnerBasisWhoOwnsNothingGetsTenantCollectionsOnly() {
        callAs("system:schedule:nightly");

        search.searchAs("q", 5, Set.of(), false, KnowledgeAccessService.Principal.ofOwner(MEMBER));

        searched(TENANT_MODEL);
    }

    @Test
    void anMcpKeyContextCannotReachRestrictedCollectionsEvenWhenTheGrantExposesThem() {
        callAs("mcp-external-key", "ROLE_mcp-external");

        assertTrue(search.search("q", 5, Set.of(privateCollection.getId(), sharedCollection.getId())).isEmpty());

        searched();
    }

    @Test
    void withNoCallerAtAllNothingIsSearchedNotEvenATenantCollection() {
        TenantContext.clear();

        assertEquals(List.of(), search.search("q", 5, Set.of()));

        searched();
    }

    @Test
    void aTenantAdminReadsEverythingIncludingPrivate() {
        callAs("admin-sub", "ROLE_tenant-admin");
        when(sharing.isAdminFor(eq(ResourceType.KNOWLEDGE_COLLECTION), any())).thenReturn(true);

        search.search("q", 5, Set.of());

        searched(PRIVATE_MODEL, SHARED_MODEL, TENANT_MODEL);
    }

    @Test
    void theExplicitSystemScopeReadsEverything() {
        callAs("system:retention");

        search.searchAs("q", 5, Set.of(), false, KnowledgeAccessService.Principal.SYSTEM);

        searched(PRIVATE_MODEL, SHARED_MODEL, TENANT_MODEL);
    }
}
