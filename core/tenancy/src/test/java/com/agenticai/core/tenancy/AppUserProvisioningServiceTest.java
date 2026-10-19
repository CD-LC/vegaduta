package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.dto.AccountResponse;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * B10 regression: a member with no {@code app_user} row (created through
 * {@code POST /api/admin/users}) used to make {@code GET /api/account} throw
 * IllegalStateException (HTTP 500). The row is now provisioned from the JWT.
 */
class AppUserProvisioningServiceTest {

    private static final String SCHEMA = "tenant_acme";
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String KC_ID = "kc-member-1";

    private AppUserRepository users;
    private TenantRepository tenants;
    private AppUserProvisioningService service;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        tenants = mock(TenantRepository.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Tenant tenant = mock(Tenant.class);
        when(tenant.getId()).thenReturn(TENANT_ID);
        when(tenants.findBySchemaName(SCHEMA)).thenReturn(Optional.of(tenant));
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        service = new AppUserProvisioningService(users, tenants, tm);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createsTheMissingRow() {
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());

        AppUser created = service.ensure(SCHEMA, KC_ID, "m@example.com", false);

        assertEquals(TENANT_ID, created.getTenantId());
        assertEquals(KC_ID, created.getKeycloakUserId());
        assertEquals("m@example.com", created.getEmail());
        assertEquals(UserRole.DELEGATED_USER, created.getRole());
    }

    @Test
    void tenantAdminFlagMapsToTenantAdminRole() {
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());
        assertEquals(UserRole.TENANT_ADMIN, service.ensure(SCHEMA, KC_ID, "a@example.com", true).getRole());
    }

    @Test
    void isIdempotentWhenTheRowExists() {
        AppUser row = new AppUser(TENANT_ID, KC_ID, "m@example.com", UserRole.DELEGATED_USER);
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.of(row));

        assertSame(row, service.ensure(SCHEMA, KC_ID, "m@example.com", false));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void losingTheInsertRaceReturnsTheWinnerRow() {
        AppUser winner = new AppUser(TENANT_ID, KC_ID, "m@example.com", UserRole.DELEGATED_USER);
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty(), Optional.of(winner));
        when(users.saveAndFlush(any(AppUser.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertSame(winner, service.ensure(SCHEMA, KC_ID, "m@example.com", false));
    }

    @Test
    void staleRowForTheSameEmailIsRelinked() {
        AppUser stale = new AppUser(TENANT_ID, "old-kc-id", "m@example.com", UserRole.DELEGATED_USER);
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(AppUser.class)))
            .thenThrow(new DataIntegrityViolationException("dup"))
            .thenAnswer(i -> i.getArgument(0));
        when(users.findByTenantIdAndEmail(TENANT_ID, "m@example.com")).thenReturn(Optional.of(stale));
        when(users.findById(any())).thenReturn(Optional.of(stale));

        AppUser repaired = service.ensure(SCHEMA, KC_ID, "m@example.com", false);

        assertEquals(KC_ID, repaired.getKeycloakUserId());
    }

    @Test
    void refusesWithoutEmailOrKnownTenant() {
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.ensure(SCHEMA, KC_ID, null, false));
        assertThrows(IllegalStateException.class, () -> service.ensure(null, KC_ID, "m@example.com", false));
        assertThrows(IllegalStateException.class, () -> service.ensure("nope", KC_ID, "m@example.com", false));
    }

    // ---- GET /api/account path ----

    @Test
    void accountEndpointProvisionsAMissingRowInsteadOfThrowing() {
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());
        TenantContext.set(TenantContext.of(SCHEMA, KC_ID, Set.of("ROLE_agent-builder")));
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60),
            Map.of("alg", "none"), Map.of("sub", KC_ID, "email", "m@example.com"));
        Principal principal = new JwtAuthenticationToken(jwt);
        AccountController controller = new AccountController(users, mock(FileStorageService.class), service);

        AccountResponse response = controller.get(principal);

        assertEquals("m@example.com", response.email());
        verify(users).saveAndFlush(any(AppUser.class));
    }

    @Test
    void accountEndpointWithoutProvisionerKeepsTheOldFailure() {
        when(users.findByKeycloakUserId(KC_ID)).thenReturn(Optional.empty());
        AccountController controller = new AccountController(users, mock(FileStorageService.class));
        assertThrows(IllegalStateException.class, () -> controller.get(() -> KC_ID));
    }
}
