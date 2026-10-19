package com.agenticai.core.tenancy.apikey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.PermissionAuthoritySource;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.apikey.ApiKeyAuthenticationToken;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreateRequest;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreatedResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class TenantApiKeyServiceTest {

    private TenantApiKeyRepository repository;
    private TenantResolutionService tenantResolutionService;
    private AuditLogService auditLogService;
    private TenantLimits tenantLimits;
    private PermissionAuthoritySource permissionSource;
    private TenantApiKeyService service;

    private static ObjectProvider<PermissionAuthoritySource> providerOf(PermissionAuthoritySource source) {
        ObjectProvider<PermissionAuthoritySource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return provider;
    }

    @BeforeEach
    void setUp() {
        repository = mock(TenantApiKeyRepository.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        auditLogService = mock(AuditLogService.class);
        tenantLimits = mock(TenantLimits.class);
        permissionSource = mock(PermissionAuthoritySource.class);
        service = new TenantApiKeyService(
            repository, tenantResolutionService, auditLogService, tenantLimits,
            providerOf(permissionSource));
        when(repository.save(any(TenantApiKey.class))).thenAnswer(inv -> {
            TenantApiKey e = inv.getArgument(0);
            if (e.getId() == null) {
                ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            }
            return e;
        });
        when(tenantLimits.developerApiAllowed(anyString())).thenReturn(true);
        TenantContext.set(TenantContext.of("tenant_acme", "admin-user", Set.of("ROLE_tenant-admin")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createReturnsRawKeyOnceAndStoresHashOnly() {
        when(permissionSource.effectivePermissions(eq("tenant_acme"), eq("admin-user"), anySet()))
            .thenReturn(Set.of("MANAGE_WORKFLOWS_ADMIN", "MANAGE_AGENTS"));
        TenantApiKeyCreatedResponse created = service.create(
            new TenantApiKeyCreateRequest("ci-runner", Set.of("MANAGE_WORKFLOWS_ADMIN"), null));
        assertTrue(created.apiKey().startsWith("vdak_"));
        assertEquals(Set.of("MANAGE_WORKFLOWS_ADMIN"), created.key().permissions());
        // The stored row holds the hash + display prefix, never the plaintext.
        var entity = capturedEntity();
        assertFalse(entity.getApiKeyHash().equals(created.apiKey()));
        assertEquals(64, entity.getApiKeyHash().length());
        assertEquals(8, entity.getApiKeyDisplayPrefix().length());
        verify(tenantResolutionService).link(
            eq(ExternalAccountProvider.TENANT_API_KEY), eq(entity.getApiKeyHash()), eq("tenant_acme"));
        verify(auditLogService).record(eq("admin-user"), eq("tenancy.apikey.created"),
            eq("TenantApiKey"), anyString(), any(), anyString());
    }

    @Test
    void createRejectsPermissionsTheIssuerDoesNotHold() {
        when(permissionSource.effectivePermissions(eq("tenant_acme"), eq("admin-user"), anySet()))
            .thenReturn(Set.of("MANAGE_BILLING"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            service.create(new TenantApiKeyCreateRequest("sneaky", Set.of("MANAGE_USERS"), null)));
        assertTrue(ex.getMessage().contains("issuer"));
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsUnknownPermissionNames() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            service.create(new TenantApiKeyCreateRequest("bad", Set.of("NOT_A_PERMISSION"), null)));
        assertTrue(ex.getMessage().contains("Unknown permission"));
    }

    @Test
    void createRejectsPastExpiry() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        assertThrows(IllegalArgumentException.class, () ->
            service.create(new TenantApiKeyCreateRequest("expired", Set.of(), past)));
    }

    @Test
    void createFailsWhenPermissionStoreAbsentAndScopesRequested() {
        service = new TenantApiKeyService(
            repository, tenantResolutionService, auditLogService, tenantLimits, providerOf(null));
        assertThrows(IllegalStateException.class, () ->
            service.create(new TenantApiKeyCreateRequest("scoped", Set.of("MANAGE_AGENTS"), null)));
    }

    @Test
    void rotateMintsNewHashAndUnlinksOld() {
        TenantApiKey entity = new TenantApiKey("k", "oldhash", "prefix123", "[]", "u", null);
        UUID id = withId(entity);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(permissionSource.effectivePermissions(anyString(), anyString(), anySet()))
            .thenReturn(Set.of());
        TenantApiKeyCreatedResponse rotated = service.rotate(id);
        assertTrue(rotated.apiKey().startsWith("vdak_"));
        assertFalse(entity.getApiKeyHash().equals("oldhash"));
        verify(tenantResolutionService).unlink(ExternalAccountProvider.TENANT_API_KEY, "oldhash");
    }

    @Test
    void revokeAuditsFullStateThenDeletes() {
        TenantApiKey entity = new TenantApiKey("k", "hash", "prefix", "[\"MANAGE_AGENTS\"]", "u", null);
        UUID id = withId(entity);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        service.revoke(id);
        verify(auditLogService).record(anyString(), eq("tenancy.apikey.revoked"), eq("TenantApiKey"),
            eq(id.toString()), any(), anyString());
        verify(tenantResolutionService).unlink(ExternalAccountProvider.TENANT_API_KEY, "hash");
        verify(repository).delete(entity);
    }

    @Test
    void authenticateResolvesTenantThenVerifiesRow() {
        // The SPI is called BEFORE TenantContextFilter - it must own the
        // context hop and leave none behind.
        TenantContext.clear();
        TenantApiKey entity = new TenantApiKey(
            "build-bot", TenantApiKeyHasher.sha256Hex("vdak_secret"), "secret12",
            "[\"MANAGE_WORKFLOWS_ADMIN\"]", "admin-user", null);
        withId(entity);
        when(tenantResolutionService.resolveTenantSchema(eq(ExternalAccountProvider.TENANT_API_KEY), anyString()))
            .thenReturn(Optional.of("tenant_acme"));
        when(repository.findByApiKeyHash(TenantApiKeyHasher.sha256Hex("vdak_secret")))
            .thenReturn(Optional.of(entity));

        Optional<ApiKeyAuthenticationToken> auth = service.authenticate("vdak_secret");

        assertTrue(auth.isPresent());
        assertEquals("tenant_acme", auth.get().getTenantId());
        assertEquals(Set.of("MANAGE_WORKFLOWS_ADMIN"), auth.get().getPermissionNames());
        assertTrue(auth.get().getPrincipal().toString().startsWith("apikey:build-bot:"));
        assertTrue(auth.get().getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("PERM_MANAGE_WORKFLOWS_ADMIN")));
        assertNotNull(entity.getLastUsedAt());
        // The lookup's scratch context is cleared for the real request path.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void authenticateRejectsDisabledExpiredUnknownAndPrefixless() {
        TenantContext.clear();
        TenantApiKey disabled = new TenantApiKey("k", TenantApiKeyHasher.sha256Hex("vdak_a"), "a1234567", "[]", "u", null);
        disabled.setEnabled(false);
        TenantApiKey expired = new TenantApiKey("k", TenantApiKeyHasher.sha256Hex("vdak_b"), "b1234567", "[]", "u",
            Instant.now().minus(1, ChronoUnit.DAYS));
        withId(disabled);
        withId(expired);
        when(tenantResolutionService.resolveTenantSchema(eq(ExternalAccountProvider.TENANT_API_KEY), anyString()))
            .thenReturn(Optional.of("tenant_acme"));
        when(repository.findByApiKeyHash(TenantApiKeyHasher.sha256Hex("vdak_a")))
            .thenReturn(Optional.of(disabled));
        when(repository.findByApiKeyHash(TenantApiKeyHasher.sha256Hex("vdak_b")))
            .thenReturn(Optional.of(expired));
        when(repository.findByApiKeyHash(TenantApiKeyHasher.sha256Hex("vdak_unknown")))
            .thenReturn(Optional.empty());

        assertTrue(service.authenticate("vdak_a").isEmpty());
        assertTrue(service.authenticate("vdak_b").isEmpty());
        assertTrue(service.authenticate("vdak_unknown").isEmpty());
        assertTrue(service.authenticate("not-a-key").isEmpty());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void authenticateReturnsEmptyWhenLinkDoesNotResolve() {
        TenantContext.clear();
        when(tenantResolutionService.resolveTenantSchema(any(), anyString()))
            .thenReturn(Optional.empty());
        assertTrue(service.authenticate("vdak_anything").isEmpty());
        verify(repository, never()).findByApiKeyHash(anyString());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void listAllMapsEveryRow() {
        TenantApiKey a = new TenantApiKey("a", "ha", "pa", "[]", "u", null);
        TenantApiKey b = new TenantApiKey("b", "hb", "pb", "[]", "u",
            Instant.now().plus(1, ChronoUnit.DAYS));
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(a, b));
        assertEquals(2, service.listAll().size());
        assertNull(service.listAll().get(0).lastUsedAt());
        assertFalse(service.listAll().get(1).expired());
    }

    private UUID withId(TenantApiKey entity) {
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(entity, "id", id);
        return id;
    }

    private TenantApiKey capturedEntity() {
        var captor = org.mockito.ArgumentCaptor.forClass(TenantApiKey.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
