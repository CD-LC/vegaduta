package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionCreateRequest;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionResponse;
import com.agenticai.core.knowledge.dto.KnowledgeCollectionUpdateRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.sharing.ResourceAccessService;
import com.agenticai.core.sharing.ResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Ownership, visibility and the collection-level guards in {@link KnowledgeCollectionService}. */
class KnowledgeCollectionServiceAclTest {

    private static final String OWNER = "owner-sub";
    private static final String MEMBER = "member-sub";

    private KnowledgeCollectionRepository repository;
    private ResourceAccessService sharing;
    private KnowledgeCollectionService service;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeCollectionRepository.class);
        sharing = mock(ResourceAccessService.class);
        when(sharing.activeGrantLevels(eq(ResourceType.KNOWLEDGE_COLLECTION), any())).thenReturn(Map.of());
        KnowledgeAccessService access = new KnowledgeAccessService(
            repository, mock(KnowledgeDocumentRepository.class), sharing, mock(AuditLogService.class));
        service = new KnowledgeCollectionService(
            repository, mock(JdbcTemplate.class), mock(KnowledgeDocumentService.class), mock(TenantLimits.class));
        service.setAccess(access);
        when(repository.save(any(KnowledgeCollection.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void loginAs(String subject) {
        // No tenant id: skips the plan-limit gate in create(), like KnowledgeCollectionServiceTest.
        TenantContext.set(TenantContext.of(null, subject, Set.of()));
    }

    private KnowledgeCollection stored(String owner, KnowledgeVisibility visibility) {
        KnowledgeCollection c = new KnowledgeCollection("c", "d", null, "minilm-l6-v2", "simple", owner, visibility);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        when(repository.findById(c.getId())).thenReturn(Optional.of(c));
        when(repository.existsById(c.getId())).thenReturn(true);
        return c;
    }

    @Test
    void aNewCollectionIsOwnedByItsCreatorAndTenantVisibleByDefault() {
        loginAs(OWNER);

        KnowledgeCollectionResponse response =
            service.create(new KnowledgeCollectionCreateRequest("Docs", "d", null, null, null));

        assertEquals(OWNER, response.createdBy());
        assertEquals(KnowledgeVisibility.TENANT, response.visibility());
    }

    @Test
    void aCollectionCanBeCreatedPrivateOrShared() {
        loginAs(OWNER);

        KnowledgeCollectionResponse response = service.create(new KnowledgeCollectionCreateRequest(
            "Mine", "d", null, null, null, null, null, KnowledgeVisibility.PRIVATE));

        assertEquals(KnowledgeVisibility.PRIVATE, response.visibility());
        assertEquals(OWNER, response.createdBy());
    }

    @Test
    void aRestrictedCollectionNeedsAnOwnerSoWithoutAUserItIsRefused() {
        TenantContext.clear();

        assertThrows(IllegalArgumentException.class, () -> service.create(new KnowledgeCollectionCreateRequest(
            "Mine", "d", null, null, null, null, null, KnowledgeVisibility.SHARED)));
        verify(repository, never()).save(any());
    }

    @Test
    void listAllOmitsCollectionsTheCallerCannotRead() {
        loginAs(MEMBER);
        KnowledgeCollection open = stored(OWNER, KnowledgeVisibility.TENANT);
        KnowledgeCollection secret = stored(OWNER, KnowledgeVisibility.PRIVATE);
        KnowledgeCollection legacy = stored(null, null);
        when(repository.findAll()).thenReturn(List.of(open, secret, legacy));

        List<UUID> ids = service.listAll().stream().map(KnowledgeCollectionResponse::id).toList();

        assertEquals(List.of(open.getId(), legacy.getId()), ids);
    }

    @Test
    void getOnAPrivateCollectionIsA404ForANonOwnerAndWorksForTheOwner() {
        KnowledgeCollection secret = stored(OWNER, KnowledgeVisibility.PRIVATE);

        loginAs(MEMBER);
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.get(secret.getId()));

        loginAs(OWNER);
        assertEquals(secret.getId(), service.get(secret.getId()).id());
    }

    @Test
    void onlyTheOwnerOrAnAdminMayChangeVisibilityEvenOnATenantWideCollection() {
        KnowledgeCollection open = stored(OWNER, KnowledgeVisibility.TENANT);
        KnowledgeCollectionUpdateRequest lockDown =
            new KnowledgeCollectionUpdateRequest("c", "d", null, null, null, KnowledgeVisibility.PRIVATE);

        loginAs(MEMBER);
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.update(open.getId(), lockDown));
        assertEquals(KnowledgeVisibility.TENANT, open.getVisibility());

        loginAs(OWNER);
        assertEquals(KnowledgeVisibility.PRIVATE, service.update(open.getId(), lockDown).visibility());
    }

    @Test
    void anUpdateWithoutAVisibilityLeavesItUnchanged() {
        KnowledgeCollection team = stored(OWNER, KnowledgeVisibility.SHARED);
        loginAs(OWNER);

        service.update(team.getId(), new KnowledgeCollectionUpdateRequest("renamed", "d", null));

        assertEquals(KnowledgeVisibility.SHARED, team.getVisibility());
    }

    @Test
    void aMemberCannotDeleteOrEditARestrictedCollection() {
        KnowledgeCollection secret = stored(OWNER, KnowledgeVisibility.SHARED);
        loginAs(MEMBER);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.delete(secret.getId()));
        assertThrows(KnowledgeCollectionNotFoundException.class,
            () -> service.update(secret.getId(), new KnowledgeCollectionUpdateRequest("x", "d", null)));
        verify(repository, never()).delete(any());
    }

    @Test
    void requireReadableIsA404ForAnUnreadableCollection() {
        KnowledgeCollection secret = stored(OWNER, KnowledgeVisibility.PRIVATE);
        loginAs(MEMBER);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> service.requireReadable(secret.getId()));
    }
}
