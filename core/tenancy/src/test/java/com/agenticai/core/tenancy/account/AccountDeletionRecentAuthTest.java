package com.agenticai.core.tenancy.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.admin.keycloak.KeycloakAdminClient;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.AppUserRepository;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.aireport.AiContentReportRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** {@code app.account.deletion.max-auth-age}: DELETE /api/account needs a recent interactive sign-in. */
class AccountDeletionRecentAuthTest {

    private KeycloakAdminClient keycloak;
    private TenantDataErasureService erasure;
    private AppUserRepository appUsers;
    private AiContentReportRepository reports;

    @BeforeEach
    void setUp() {
        keycloak = mock(KeycloakAdminClient.class);
        erasure = mock(TenantDataErasureService.class);
        appUsers = mock(AppUserRepository.class);
        reports = mock(AiContentReportRepository.class);
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of("ROLE_tenant-admin")));
        when(keycloak.listUsersByTenant("acme")).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private AccountSelfDeletionService service(Duration maxAge) {
        return new AccountSelfDeletionService(keycloak, erasure, appUsers, mock(FileStorageService.class),
            reports, mock(AuditLogService.class), mock(ApplicationEventPublisher.class), maxAge);
    }

    private static void signedInWith(Instant authTime) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
            .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        if (authTime != null) {
            jwt.claim("auth_time", authTime);
        }
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt.build(), null));
    }

    @Test
    void staleAuthTimeIsRejectedWithReauthRequiredAndNothingIsDeleted() {
        signedInWith(Instant.now().minus(Duration.ofHours(2)));
        ReauthRequiredException ex = assertThrows(ReauthRequiredException.class,
            () -> service(Duration.ofMinutes(15)).deleteCurrentAccount("DELETE"));
        assertEquals("reauth_required", ReauthRequiredException.CODE);
        assertEquals(900L, ex.getMaxAgeSeconds());
        verifyNoInteractions(erasure, appUsers, reports);
    }

    @Test
    void missingAuthTimeClaimCountsAsStale() {
        signedInWith(null);
        assertThrows(ReauthRequiredException.class,
            () -> service(Duration.ofMinutes(15)).deleteCurrentAccount("DELETE"));
        verifyNoInteractions(erasure, appUsers, reports);
    }

    @Test
    void noJwtAtAllCountsAsStale() {
        assertThrows(ReauthRequiredException.class,
            () -> service(Duration.ofMinutes(15)).deleteCurrentAccount("DELETE"));
    }

    @Test
    void freshAuthTimeProceedsToDeletion() {
        signedInWith(Instant.now().minusSeconds(30));
        AccountSelfDeletionService.Result result = service(Duration.ofMinutes(15)).deleteCurrentAccount("DELETE");
        assertTrue(result.deleted());
    }

    @Test
    void zeroDisablesTheCheckEvenWithoutAnyClaim() {
        signedInWith(null);
        assertTrue(service(Duration.ZERO).deleteCurrentAccount("DELETE").deleted());
    }
}
