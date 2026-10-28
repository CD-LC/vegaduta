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
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.knowledge.KnowledgeAccessService.Access;
import com.agenticai.core.knowledge.KnowledgeAccessService.Principal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
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

/**
 * The per-collection access matrix: owner / visibility / grant / admin, the fail-closed cases, and
 * that every refusal is audited and 404-shaped. Plain Mockito, no Spring context.
 */
class KnowledgeAccessServiceTest {

    private static final String OWNER = "owner-sub";
    private static final String MEMBER = "member-sub";

    private KnowledgeCollectionRepository collections;
    private KnowledgeDocumentRepository documents;
    private ResourceAccessService sharing;
    private AuditLogService audit;
    private KnowledgeAccessService access;

    @BeforeEach
    void setUp() {
        collections = mock(KnowledgeCollectionRepository.class);
        documents = mock(KnowledgeDocumentRepository.class);
        sharing = mock(ResourceAccessService.class);
        audit = mock(AuditLogService.class);
        access = new KnowledgeAccessService(collections, documents, sharing, audit);
        when(sharing.activeGrantLevels(eq(ResourceType.KNOWLEDGE_COLLECTION), any())).thenReturn(Map.of());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void loginAs(String subject, String... roles) {
        TenantContext context = TenantContext.of("tenant-1", subject, Set.of(roles));
        TenantContext.set(context);
        when(sharing.isAdminFor(ResourceType.KNOWLEDGE_COLLECTION, context))
            .thenReturn(Set.of(roles).contains("ROLE_tenant-admin"));
    }

    private KnowledgeCollection collection(String owner, KnowledgeVisibility visibility) {
        KnowledgeCollection c = new KnowledgeCollection("c", "d", null, "minilm-l6-v2", "simple", owner, visibility);
        // @GeneratedValue ids are assigned on persist; unit tests need one for grant lookup.
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        when(collections.findById(c.getId())).thenReturn(Optional.of(c));
        return c;
    }

    private void grant(KnowledgeCollection c, String subject, PermissionLevel level) {
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, subject)).thenReturn(Map.of(c.getId(), level));
    }

    // --- backward compatibility ----------------------------------------------------------------

    @Test
    void aLegacyCollectionWithNoOwnerStaysFullyVisibleAndWritableForEveryMember() {
        loginAs(MEMBER);
        KnowledgeCollection legacy = collection(null, null); // V710 default: no owner, TENANT

        assertEquals(KnowledgeVisibility.TENANT, legacy.getVisibility());
        assertEquals(Access.WRITE, access.accessOf(legacy, access.currentPrincipal()));
        access.requireRead(legacy.getId());
        access.requireWrite(legacy.getId());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTenantCollectionOwnedBySomeoneElseIsStillOpenToMembers() {
        loginAs(MEMBER);
        KnowledgeCollection shared = collection(OWNER, KnowledgeVisibility.TENANT);

        access.requireRead(shared.getId());
        access.requireModify(shared); // historic rule for a tenant-wide collection
    }

    // --- restricted visibility -----------------------------------------------------------------

    @Test
    void aPrivateCollectionIsInvisibleToANonOwnerAndTheDenialIsAuditedAs404() {
        loginAs(MEMBER);
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireRead(secret.getId()));
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireWrite(secret.getId()));

        verify(audit, org.mockito.Mockito.times(2)).record(
            eq(MEMBER), eq(KnowledgeAccessService.ACTION_DENIED), eq("knowledge_collection"),
            eq(secret.getId().toString()), eq(AuditOutcome.DENIED), any());
    }

    @Test
    void theOwnerAndATenantAdminAlwaysHaveFullAccessToAPrivateCollection() {
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);

        loginAs(OWNER);
        assertEquals(Access.MANAGE, access.accessOf(secret, access.currentPrincipal()));

        loginAs("admin-sub", "ROLE_tenant-admin");
        assertEquals(Access.MANAGE, access.accessOf(secret, access.currentPrincipal()));
    }

    @Test
    void grantsDoNotOpenAPrivateCollectionButDoOpenASharedOne() {
        loginAs(MEMBER);
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);
        KnowledgeCollection team = collection(OWNER, KnowledgeVisibility.SHARED);
        grant(secret, MEMBER, PermissionLevel.EDITOR);
        when(sharing.activeGrantLevels(ResourceType.KNOWLEDGE_COLLECTION, MEMBER))
            .thenReturn(Map.of(secret.getId(), PermissionLevel.EDITOR, team.getId(), PermissionLevel.VIEWER));

        assertEquals(Access.NONE, access.accessOf(secret, access.currentPrincipal()));
        assertEquals(Access.READ, access.accessOf(team, access.currentPrincipal()));
    }

    @Test
    void viewerCanReadButNotWriteAndEditorCanWriteButNotManageAndRunnerGetsNothing() {
        KnowledgeCollection team = collection(OWNER, KnowledgeVisibility.SHARED);
        loginAs(MEMBER);

        grant(team, MEMBER, PermissionLevel.VIEWER);
        assertEquals(Access.READ, access.accessOf(team, access.currentPrincipal()));
        access.requireRead(team.getId());
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireWrite(team.getId()));

        grant(team, MEMBER, PermissionLevel.EDITOR);
        assertEquals(Access.WRITE, access.accessOf(team, access.currentPrincipal()));
        access.requireWrite(team.getId());
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireManage(team));
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireModify(team));

        grant(team, MEMBER, PermissionLevel.RUNNER);
        assertEquals(Access.NONE, access.accessOf(team, access.currentPrincipal()));
    }

    @Test
    void aLegacyCollectionCanOnlyBeRestrictedByAnAdministrator() {
        KnowledgeCollection legacy = collection(null, KnowledgeVisibility.TENANT);

        loginAs(MEMBER);
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireManage(legacy));

        loginAs("admin-sub", "ROLE_tenant-admin");
        access.requireManage(legacy);
    }

    // --- fail closed ---------------------------------------------------------------------------

    @Test
    void withNoResolvableCallerNothingIsReadableNotEvenATenantCollection() {
        KnowledgeCollection open = collection(OWNER, KnowledgeVisibility.TENANT);

        TenantContext.clear();
        assertEquals(Principal.NONE, access.currentPrincipal());
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireRead(open.getId()));
        assertEquals(List.of(), access.filterReadable(List.of(open), access.currentPrincipal(), false));

        TenantContext.set(TenantContext.of("tenant-1", "  ", Set.of()));
        assertEquals(Principal.NONE, access.currentPrincipal());
    }

    @Test
    void theExplicitSystemScopeSeesEverythingButAnOwnerPrincipalNeverGetsTheAdminOverride() {
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);

        assertEquals(Access.MANAGE, access.accessOf(secret, Principal.SYSTEM));
        assertEquals(Access.MANAGE, access.accessOf(secret, Principal.ofOwner(OWNER)));
        assertEquals(Access.NONE, access.accessOf(secret, Principal.ofOwner(MEMBER)));
        assertEquals(Principal.NONE, Principal.ofOwner(" "));
    }

    // --- filtering -----------------------------------------------------------------------------

    @Test
    void filterReadableKeepsOrderDropsTheUnreadableAndAuditsOnlyExplicitRequests() {
        loginAs(MEMBER);
        KnowledgeCollection open = collection(OWNER, KnowledgeVisibility.TENANT);
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);
        KnowledgeCollection legacy = collection(null, null);

        List<KnowledgeCollection> unscoped = access.filterReadable(
            List.of(open, secret, legacy), access.currentPrincipal(), false);
        assertEquals(List.of(open, legacy), unscoped);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());

        List<KnowledgeCollection> named = access.filterReadable(
            List.of(secret, open), access.currentPrincipal(), true);
        assertEquals(List.of(open), named);
        verify(audit).record(eq(MEMBER), eq(KnowledgeAccessService.ACTION_DENIED), any(),
            eq(secret.getId().toString()), eq(AuditOutcome.DENIED), any());
    }

    @Test
    void anAuditFailureNeverTurnsADenialIntoSuccessOrAServerError() {
        loginAs(MEMBER);
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);
        when(audit.record(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireRead(secret.getId()));
    }

    // --- documents and cross-tenant ids --------------------------------------------------------

    @Test
    void documentAccessFollowsTheDocumentsCollection() {
        loginAs(MEMBER);
        KnowledgeCollection secret = collection(OWNER, KnowledgeVisibility.PRIVATE);
        KnowledgeDocument doc = mock(KnowledgeDocument.class);
        when(doc.getCollectionId()).thenReturn(secret.getId());
        UUID docId = UUID.randomUUID();
        when(documents.findById(docId)).thenReturn(Optional.of(doc));

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireReadDocument(docId));
        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireWriteDocument(docId));
    }

    @Test
    void idsUnknownToTheCallersTenantSchemaAre404AndNeverLookLikeAnAccessDecision() {
        // Repositories resolve inside the caller's own tenant schema, so another tenant's collection
        // or document id is simply absent: not found, and nothing is audited as a denial.
        loginAs(MEMBER);
        UUID foreignCollection = UUID.randomUUID();
        UUID foreignDocument = UUID.randomUUID();
        when(collections.findById(foreignCollection)).thenReturn(Optional.empty());
        when(documents.findById(foreignDocument)).thenReturn(Optional.empty());

        assertThrows(KnowledgeCollectionNotFoundException.class, () -> access.requireRead(foreignCollection));
        assertThrows(KnowledgeDocumentNotFoundException.class, () -> access.requireReadDocument(foreignDocument));
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }
}
