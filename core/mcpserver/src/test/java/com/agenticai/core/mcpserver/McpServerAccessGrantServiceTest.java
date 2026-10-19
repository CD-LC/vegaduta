package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreateRequest;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreatedResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link McpServerAccessGrantService} owns the full inbound-API-key
 * lifecycle AND the two-step auth resolution the filter runs on every
 * external call. The security invariants pinned here, in priority order:
 * the plaintext key is returned exactly once and only its SHA-256 hash is
 * ever persisted or looked up; a key is linked to the ISSUING tenant's
 * schema (a grant created under tenant A can only ever resolve to tenant A
 * - which, combined with the tenant-scoped repository lookup in step 2, is
 * why tenant A's key can never authorize tenant B's resources); rotation
 * repoints the control_plane link so the old key dies immediately; disabled
 * grants are filtered on the hot path; and revocation records the forensic
 * audit row BEFORE the hard delete destroys the grant.
 *
 * <p>DB-free Mockito (repository/tenancy/audit mocked); entities are
 * constructed for real with the JPA id backfilled via reflection where
 * needed - same posture as {@code McpRegistryServiceTest} in core/mcp.
 */
class McpServerAccessGrantServiceTest {

    private McpServerAccessGrantRepository repository;
    private AgentService agentService;
    private TenantResolutionService tenantResolutionService;
    private AuditLogService auditLogService;
    private com.agenticai.core.security.TenantLimits tenantLimits;
    private McpServerAccessGrantService service;

    @BeforeEach
    void setUp() {
        repository = mock(McpServerAccessGrantRepository.class);
        agentService = mock(AgentService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        auditLogService = mock(AuditLogService.class);
        // The developer-API plan-gate: this suite acts as a tenant that HAS the
        // entitlement; grant-management denial is covered by the gate's own
        // null-tenant skip plus actAsTenant scenarios.
        tenantLimits = mock(com.agenticai.core.security.TenantLimits.class);
        when(tenantLimits.developerApiAllowed(any())).thenReturn(true);
        service = new McpServerAccessGrantService(repository, agentService, tenantResolutionService, auditLogService, tenantLimits);
        // Mirror JPA's id assignment on save - create()/rotate() read
        // entity.getId() for the audit row immediately after saving.
        when(repository.save(any())).thenAnswer(inv -> {
            McpServerAccessGrant entity = inv.getArgument(0);
            if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
            }
            return entity;
        });
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenant(String tenantSchema) {
        TenantContext.set(TenantContext.of(tenantSchema, "admin@" + tenantSchema, Set.of("PERM_MANAGE_INTEGRATIONS")));
    }

    private McpServerAccessGrant grantWithId(UUID id, String rawKey) {
        McpServerAccessGrant grant = new McpServerAccessGrant(
            "existing", McpApiKeyHasher.sha256Hex(rawKey), McpApiKeyHasher.displayPrefix(rawKey), "[]", "[]", "[]");
        ReflectionTestUtils.setField(grant, "id", id);
        return grant;
    }

    // ---- create -------------------------------------------------------------

    @Test
    void createPersistsOnlyTheHashAndReturnsTheRawKeyExactlyOnce() {
        actAsTenant("tenant_alpha");
        UUID agentId = UUID.randomUUID();

        McpServerAccessGrantCreatedResponse response = service.create(
            new McpServerAccessGrantCreateRequest("ci key", Set.of(agentId), null, null));

        assertNotNull(response.rawApiKey());
        assertTrue(response.rawApiKey().startsWith("vmcp_"));

        ArgumentCaptor<McpServerAccessGrant> saved = ArgumentCaptor.forClass(McpServerAccessGrant.class);
        verify(repository).save(saved.capture());
        // The "shown once" contract's storage half: hash only, never the key.
        assertEquals(McpApiKeyHasher.sha256Hex(response.rawApiKey()), saved.getValue().getApiKeyHash());
        assertNotEquals(response.rawApiKey(), saved.getValue().getApiKeyHash());
        assertEquals(McpApiKeyHasher.displayPrefix(response.rawApiKey()), saved.getValue().getApiKeyDisplayPrefix());
        assertTrue(saved.getValue().isEnabled(), "a freshly created grant is usable immediately");
        // And the response DTO half: the grant view carries the display
        // prefix, never the hash (McpServerAccessGrantResponse has no hash
        // field by design - assert the prefix is what round-tripped).
        assertEquals(saved.getValue().getApiKeyDisplayPrefix(), response.grant().apiKeyDisplayPrefix());
    }

    @Test
    void createLinksTheHashToTheIssuingTenantsOwnSchema() {
        // THE cross-tenant scoping seam: the control_plane link is what step
        // 1 of inbound auth resolves, so linking to the wrong schema would
        // let a key issued by tenant A open tenant B's schema. It must be
        // the CURRENT TenantContext's tenant, and the linked id must be the
        // hash (control_plane must never hold plaintext either).
        actAsTenant("tenant_alpha");

        McpServerAccessGrantCreatedResponse response = service.create(
            new McpServerAccessGrantCreateRequest("ci key", null, null, null));

        verify(tenantResolutionService).link(
            ExternalAccountProvider.MCP_SERVER_KEY,
            McpApiKeyHasher.sha256Hex(response.rawApiKey()),
            "tenant_alpha");
        verify(tenantResolutionService, never()).link(any(), eq(response.rawApiKey()), anyString());
    }

    @Test
    void createWithoutATenantContextFailsInsteadOfLinkingNowhere() {
        // No ambient tenant means there is no schema to bind the key to - a
        // silent save here would orphan a live credential.
        assertThrows(IllegalStateException.class,
            () -> service.create(new McpServerAccessGrantCreateRequest("orphan", null, null, null)));
    }

    @Test
    void createValidatesEveryExposedAgentIdBeforeSaving() {
        // Fail fast on a dangling id (service javadoc) - and because
        // AgentService is itself tenant-scoped via TenantContext, this is
        // also what stops an admin exposing ANOTHER tenant's agent id: the
        // lookup simply won't resolve in their schema.
        actAsTenant("tenant_alpha");
        UUID bogusAgentId = UUID.randomUUID();
        when(agentService.get(bogusAgentId)).thenThrow(new RuntimeException("agent not found"));

        assertThrows(RuntimeException.class, () -> service.create(
            new McpServerAccessGrantCreateRequest("bad", Set.of(bogusAgentId), null, null)));

        verify(repository, never()).save(any());
        verify(tenantResolutionService, never()).link(any(), anyString(), anyString());
    }

    @Test
    void createRecordsAnAuditTrailRow() {
        actAsTenant("tenant_alpha");

        service.create(new McpServerAccessGrantCreateRequest("ci key", null, null, null));

        verify(auditLogService).record(
            eq("admin@tenant_alpha"), eq("mcpserver.grant.created"), eq("McpServerAccessGrant"),
            anyString(), eq(AuditOutcome.SUCCESS), anyString());
    }

    // ---- inbound auth resolution --------------------------------------------

    @Test
    void resolveTenantSchemaLooksUpByHashNeverByRawKey() {
        String rawKey = "vmcp_someRawKey";
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.MCP_SERVER_KEY, McpApiKeyHasher.sha256Hex(rawKey)))
            .thenReturn(Optional.of("tenant_alpha"));

        assertEquals(Optional.of("tenant_alpha"), service.resolveTenantSchema(rawKey));

        verify(tenantResolutionService, never())
            .resolveTenantSchema(ExternalAccountProvider.MCP_SERVER_KEY, rawKey);
    }

    @Test
    void findByRawKeyWithinCurrentTenantReturnsTheEnabledGrantAndStampsUsage() {
        String rawKey = "vmcp_someRawKey";
        McpServerAccessGrant grant = grantWithId(UUID.randomUUID(), rawKey);
        when(repository.findByApiKeyHash(McpApiKeyHasher.sha256Hex(rawKey))).thenReturn(Optional.of(grant));

        Optional<McpServerAccessGrant> found = service.findByRawKeyWithinCurrentTenant(rawKey);

        assertTrue(found.isPresent());
        // lastUsedAt is the admin's only "is this key actually in use"
        // signal before revoking - it must be written on the auth path.
        assertNotNull(grant.getLastUsedAt());
        verify(repository).save(grant);
    }

    @Test
    void findByRawKeyWithinCurrentTenantFiltersOutDisabledGrants() {
        // The admin's disable toggle IS this filter - the control_plane link
        // still resolves for a disabled grant, so if this returned it, the
        // kill switch would be decorative.
        String rawKey = "vmcp_someRawKey";
        McpServerAccessGrant grant = grantWithId(UUID.randomUUID(), rawKey);
        grant.setEnabled(false);
        when(repository.findByApiKeyHash(McpApiKeyHasher.sha256Hex(rawKey))).thenReturn(Optional.of(grant));

        assertTrue(service.findByRawKeyWithinCurrentTenant(rawKey).isEmpty());
        // No usage stamp for a rejected key either.
        verify(repository, never()).save(any());
    }

    @Test
    void findByRawKeyWithinCurrentTenantIsEmptyForAnUnknownHash() {
        when(repository.findByApiKeyHash(anyString())).thenReturn(Optional.empty());
        assertTrue(service.findByRawKeyWithinCurrentTenant("vmcp_unknown").isEmpty());
    }

    // ---- rotate -------------------------------------------------------------

    @Test
    void rotateRepointsTheLinkAndKillsTheOldKeyImmediately() {
        actAsTenant("tenant_alpha");
        UUID grantId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId, "vmcp_oldRawKey");
        String oldHash = grant.getApiKeyHash();
        when(repository.findById(grantId)).thenReturn(Optional.of(grant));

        McpServerAccessGrantCreatedResponse response = service.rotate(grantId);

        String newHash = McpApiKeyHasher.sha256Hex(response.rawApiKey());
        assertNotEquals(oldHash, newHash);
        assertEquals(newHash, grant.getApiKeyHash(), "the entity must carry the new hash");
        // Old key must stop resolving the instant rotate returns - link the
        // new hash, unlink the old (service javadoc's "repointed rather than
        // left stale" posture).
        verify(tenantResolutionService).link(ExternalAccountProvider.MCP_SERVER_KEY, newHash, "tenant_alpha");
        verify(tenantResolutionService).unlink(ExternalAccountProvider.MCP_SERVER_KEY, oldHash);
    }

    @Test
    void rotateUnknownGrantThrowsNotFound() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThrows(McpServerAccessGrantNotFoundException.class, () -> service.rotate(UUID.randomUUID()));
    }

    // ---- revoke -------------------------------------------------------------

    @Test
    void revokeRecordsTheForensicAuditRowBeforeDeletingTheGrant() {
        actAsTenant("tenant_alpha");
        UUID grantId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId, "vmcp_someRawKey");
        when(repository.findById(grantId)).thenReturn(Optional.of(grant));

        service.revoke(grantId);

        // Order matters and is the whole point of the 2026-07-20 audit fix:
        // the hard delete destroys name/exposed-ids/lastUsedAt forever, so
        // the audit row must exist first or an incident investigation has
        // nothing to look at.
        InOrder inOrder = inOrder(auditLogService, tenantResolutionService, repository);
        inOrder.verify(auditLogService).record(
            eq("admin@tenant_alpha"), eq("mcpserver.grant.revoked"), eq("McpServerAccessGrant"),
            eq(grantId.toString()), eq(AuditOutcome.SUCCESS), anyString());
        inOrder.verify(tenantResolutionService).unlink(ExternalAccountProvider.MCP_SERVER_KEY, grant.getApiKeyHash());
        inOrder.verify(repository).delete(grant);
    }

    @Test
    void revokeUnknownGrantThrowsNotFoundAndDeletesNothing() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        assertThrows(McpServerAccessGrantNotFoundException.class, () -> service.revoke(UUID.randomUUID()));
        verify(repository, never()).delete(any());
        verify(tenantResolutionService, never()).unlink(any(), anyString());
    }

    // ---- exposed-id parsing -------------------------------------------------

    @Test
    void parseExposedIdsRoundTripsAValidJsonArray() {
        UUID agentId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        McpServerAccessGrant grant = new McpServerAccessGrant(
            "g", "hash", "prefix",
            "[\"" + agentId + "\"]", "[\"" + workflowId + "\"]", "[\"" + collectionId + "\"]");

        assertEquals(Set.of(agentId), service.parseExposedAgentIds(grant));
        assertEquals(Set.of(workflowId), service.parseExposedWorkflowIds(grant));
        assertEquals(Set.of(collectionId), service.parseExposedKnowledgeCollectionIds(grant));
    }

    @Test
    void parseExposedIdsTreatsMalformedJsonAsEmptyNotEverything() {
        // A corrupted row must FAIL CLOSED: empty set means "nothing
        // exposed" everywhere in this module, so parse failure degrading to
        // empty is the safe direction. Degrading to a throw would 500 the
        // auth filter; degrading to "all" would be a privilege escalation.
        McpServerAccessGrant grant = new McpServerAccessGrant(
            "g", "hash", "prefix", "{not-json", "not json at all", "[\"not-a-uuid\"]");

        assertTrue(service.parseExposedAgentIds(grant).isEmpty());
        assertTrue(service.parseExposedWorkflowIds(grant).isEmpty());
        assertTrue(service.parseExposedKnowledgeCollectionIds(grant).isEmpty());
    }

    @Test
    void parseExposedIdsTreatsBlankAsEmpty() {
        McpServerAccessGrant grant = new McpServerAccessGrant("g", "hash", "prefix", "", "  ", "");

        assertTrue(service.parseExposedAgentIds(grant).isEmpty());
        assertTrue(service.parseExposedWorkflowIds(grant).isEmpty());
        assertTrue(service.parseExposedKnowledgeCollectionIds(grant).isEmpty());
    }

    // ---- setEnabled / listAll ----------------------------------------------

    @Test
    void setEnabledFlipsTheFlagAndPersists() {
        UUID grantId = UUID.randomUUID();
        McpServerAccessGrant grant = grantWithId(grantId, "vmcp_someRawKey");
        when(repository.findById(grantId)).thenReturn(Optional.of(grant));

        assertFalse(service.setEnabled(grantId, false).enabled());
        assertFalse(grant.isEnabled());
        verify(repository).save(grant);
    }

    @Test
    void listAllNeverExposesHashesOnlyDisplayPrefixes() {
        McpServerAccessGrant grant = grantWithId(UUID.randomUUID(), "vmcp_someRawKey");
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(java.util.List.of(grant));

        var responses = service.listAll();

        assertEquals(1, responses.size());
        assertEquals(grant.getApiKeyDisplayPrefix(), responses.get(0).apiKeyDisplayPrefix());
        // Structural (the DTO has no hash field), but worth a canary: no
        // string field on the response may carry the stored hash.
        assertNotEquals(grant.getApiKeyHash(), responses.get(0).apiKeyDisplayPrefix());
        assertNotEquals(grant.getApiKeyHash(), responses.get(0).name());
    }
}
