package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.crypto.EncryptionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The vault's authorization and resolution rules. These are the behaviours
 * where a plausible-looking "simplification" later would be a privilege
 * escalation rather than a bug, so each one is pinned.
 */
class SecretVaultServiceTest {

    private static final String OWNER = "user-1";
    private static final String OTHER = "user-2";

    private TenantSecretRepository repository;
    private EncryptionService encryptionService;
    private AuditLogService auditLogService;
    private SecretVaultService service;

    @BeforeEach
    void setUp() {
        repository = mock(TenantSecretRepository.class);
        encryptionService = mock(EncryptionService.class);
        auditLogService = mock(AuditLogService.class);
        service = new SecretVaultService(repository, encryptionService, auditLogService);
        when(encryptionService.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(encryptionService.decrypt(anyString())).thenAnswer(i -> i.getArgument(0).toString().substring(4));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TenantSecret tenantSecret(String name) {
        return new TenantSecret(name, SecretScope.TENANT, null, SecretKind.API_KEY,
            null, "enc:shared-value", "alue", null, false, "admin");
    }

    private TenantSecret userSecret(String name, String owner, Instant expiresAt) {
        return new TenantSecret(name, SecretScope.USER, owner, SecretKind.PASSWORD,
            null, "enc:personal-value", "alue", expiresAt, false, owner);
    }

    // ------------------------------------------------------- the write gate

    @Test
    void aNonAdminCannotRevokeASharedWorkspaceSecret() {
        TenantSecret shared = tenantSecret("STRIPE_KEY");
        when(repository.findById(shared.getId())).thenReturn(Optional.of(shared));

        assertThatThrownBy(() -> service.revoke(shared.getId(), OWNER, false))
            .isInstanceOf(SecretWriteForbiddenException.class);

        assertThat(shared.getStatus()).isEqualTo(SecretStatus.ACTIVE);
        verify(auditLogService).record(eq(OWNER), eq("SECRET_ACCESS_DENIED"), eq("SECRET"),
            eq("STRIPE_KEY"), eq(AuditOutcome.FAILURE), anyString());
    }

    @Test
    void aNonAdminCannotPurgeASharedWorkspaceSecret() {
        TenantSecret shared = tenantSecret("STRIPE_KEY");
        when(repository.findById(shared.getId())).thenReturn(Optional.of(shared));

        assertThatThrownBy(() -> service.purge(shared.getId(), OWNER, false))
            .isInstanceOf(SecretWriteForbiddenException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    void anAdminCanRevokeASharedWorkspaceSecret() {
        TenantSecret shared = tenantSecret("STRIPE_KEY");
        when(repository.findById(shared.getId())).thenReturn(Optional.of(shared));

        service.revoke(shared.getId(), OWNER, true);

        assertThat(shared.getStatus()).isEqualTo(SecretStatus.REVOKED);
    }

    @Test
    void anOrdinaryMemberCanRevokeTheirOwnPersonalSecretWithoutBeingAdmin() {
        TenantSecret mine = userSecret("MY_PORTAL_PW", OWNER, null);
        when(repository.findById(mine.getId())).thenReturn(Optional.of(mine));

        service.revoke(mine.getId(), OWNER, false);

        assertThat(mine.getStatus()).isEqualTo(SecretStatus.REVOKED);
    }

    @Test
    void anotherMembersPersonalSecretIsNotEvenAddressable() {
        TenantSecret theirs = userSecret("MY_PORTAL_PW", OTHER, null);
        when(repository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        // "No such secret", not "forbidden" - distinguishing the two would let
        // one member probe for another's secret names.
        assertThatThrownBy(() -> service.revoke(theirs.getId(), OWNER, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No such secret");
    }

    @Test
    void purgingAnUnknownSecretIsANotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purge(unknown, OWNER, true))
            .isInstanceOf(SecretNotFoundException.class)
            .hasMessage("No such secret");
    }

    @Test
    void rotatingByNameCopiesTheNewDescription() {
        TenantSecret shared = tenantSecret("STRIPE_KEY");
        when(repository.findTenantScoped("STRIPE_KEY")).thenReturn(Optional.of(shared));

        service.store("STRIPE_KEY", SecretScope.TENANT, null, SecretKind.API_KEY,
            "rotated for Q4", "a-brand-new-secret-value", null, false, "admin");

        assertThat(shared.getDescription()).isEqualTo("rotated for Q4");
    }

    // -------------------------------------------------------- resolution

    @Test
    void aPersonalSecretShadowsTheSharedOneOfTheSameName() {
        TenantSecret mine = userSecret("PORTAL", OWNER, null);
        // The repository orders USER-scoped first; this asserts the service
        // honours that order rather than re-sorting or taking the last row.
        when(repository.findResolvableByName("PORTAL", OWNER))
            .thenReturn(List.of(mine, tenantSecret("PORTAL")));

        assertThat(service.resolveForRun("PORTAL", OWNER)).contains("personal-value");
    }

    @Test
    void anExpiredPersonalSecretDoesNotFallBackToTheSharedWorkspaceAccount() {
        TenantSecret expiredMine = userSecret("PORTAL", OWNER, Instant.now().minus(1, ChronoUnit.DAYS));
        when(repository.findResolvableByName("PORTAL", OWNER))
            .thenReturn(List.of(expiredMine, tenantSecret("PORTAL")));

        // Silently acting as the workspace account would be a privilege
        // escalation wearing a convenience costume.
        assertThat(service.resolveForRun("PORTAL", OWNER)).isEmpty();
        verify(auditLogService).record(eq(OWNER), eq("SECRET_ACCESSED"), eq("SECRET"),
            anyString(), eq(AuditOutcome.FAILURE), anyString());
    }

    @Test
    void aRevokedSecretStopsResolvingButKeepsItsRow() {
        TenantSecret shared = tenantSecret("PORTAL");
        shared.revoke();
        when(repository.findResolvableByName("PORTAL", OWNER)).thenReturn(List.of(shared));

        assertThat(service.resolveForRun("PORTAL", OWNER)).isEmpty();
        verify(repository, never()).delete(any());
    }

    @Test
    void everyResolutionIsAuditedIncludingAMiss() {
        when(repository.findResolvableByName("NOPE", OWNER)).thenReturn(List.of());

        assertThat(service.resolveForRun("NOPE", OWNER)).isEmpty();

        verify(auditLogService).record(eq(OWNER), eq("SECRET_ACCESSED"), eq("SECRET"),
            eq("NOPE"), eq(AuditOutcome.FAILURE), eq("no such secret"));
    }

    @Test
    void aMalformedNameNeverReachesTheDatabase() {
        assertThat(service.resolveForRun("lower_case", OWNER)).isEmpty();
        verify(repository, never()).findResolvableByName(anyString(), anyString());
    }

    // ------------------------------------------------------------- storage

    @Test
    void storingTwiceRotatesInPlaceSoExistingReferencesKeepWorking() {
        TenantSecret existing = tenantSecret("STRIPE_KEY");
        UUID originalId = existing.getId();
        when(repository.findTenantScoped("STRIPE_KEY")).thenReturn(Optional.of(existing));

        TenantSecret result = service.store("STRIPE_KEY", SecretScope.TENANT, null,
            SecretKind.API_KEY, null, "sk_live_new_value", null, false, "admin");

        assertThat(result.getId()).isEqualTo(originalId);
        assertThat(result.getRotatedAt()).isNotNull();
        verify(repository, never()).save(any());
    }

    @Test
    void aShortSecretGetsNoPreviewAtAll() {
        when(repository.findTenantScoped("PIN")).thenReturn(Optional.empty());

        TenantSecret stored = service.store("PIN", SecretScope.TENANT, null,
            SecretKind.CUSTOM, null, "123456", null, false, "admin");

        // 4 of 6 characters is not a hint, it is most of the secret.
        assertThat(stored.getValuePreview()).isNull();
    }

    @Test
    void aUserScopedSecretAlwaysBelongsToTheCaller() {
        when(repository.findByNameAndScopeAndOwnerUserId("PORTAL", SecretScope.USER, OWNER))
            .thenReturn(Optional.empty());

        // An ownerUserId supplied by a request must not be able to plant a
        // secret that later resolves under someone else's identity... the
        // caller's own id is what is honoured when one is passed explicitly.
        TenantSecret stored = service.store("PORTAL", SecretScope.USER, null,
            SecretKind.PASSWORD, null, "hunter2hunter2", null, false, OWNER);

        assertThat(stored.getOwnerUserId()).isEqualTo(OWNER);
        assertThat(stored.getScope()).isEqualTo(SecretScope.USER);
    }

    // -------------------------------------------- the code-tool opt-in gate

    @Test
    void sandboxedCodeCannotReadASecretThatWasNotOptedIn() {
        // The default. findCodeToolReadable filters on code_tool_access, so an
        // ordinary vault secret - one used by an MCP server, say - is simply
        // not there for a code tool, no matter who granted it to the tool.
        when(repository.findCodeToolReadable("STRIPE_KEY")).thenReturn(Optional.empty());

        assertThat(service.resolveForCodeTool("STRIPE_KEY", "my_tool")).isEmpty();
    }

    @Test
    void sandboxedCodeCanReadASecretThatWasOptedIn() {
        TenantSecret opted = new TenantSecret("STRIPE_KEY", SecretScope.TENANT, null,
            SecretKind.API_KEY, null, "enc:shared-value", "alue", null, true, "admin");
        when(repository.findCodeToolReadable("STRIPE_KEY")).thenReturn(Optional.of(opted));

        assertThat(service.resolveForCodeTool("STRIPE_KEY", "my_tool")).contains("shared-value");
    }

    @Test
    void aCodeToolReadIsAuditedUnderItsOwnActionTypeNamingTheTool() {
        TenantSecret opted = new TenantSecret("STRIPE_KEY", SecretScope.TENANT, null,
            SecretKind.API_KEY, null, "enc:shared-value", "alue", null, true, "admin");
        when(repository.findCodeToolReadable("STRIPE_KEY")).thenReturn(Optional.of(opted));

        service.resolveForCodeTool("STRIPE_KEY", "my_tool");

        // Distinct from SECRET_ACCESSED so "a program read this credential" is
        // greppable and never blends into ordinary MCP/agent traffic.
        verify(auditLogService).record(
            isNull(), eq("SECRET_ACCESSED_BY_CODE"), eq("SECRET"), anyString(),
            eq(AuditOutcome.SUCCESS), contains("tool=my_tool"));
    }

    @Test
    void aMissAndANotOptedInSecretAreIndistinguishableToTheCaller() {
        // Otherwise a code tool becomes a probe for which vault names exist.
        when(repository.findCodeToolReadable(anyString())).thenReturn(Optional.empty());

        assertThat(service.resolveForCodeTool("EXISTS_BUT_NOT_OPTED", "t")).isEmpty();
        assertThat(service.resolveForCodeTool("DOES_NOT_EXIST", "t")).isEmpty();
    }

    @Test
    void theCodeToolPathNeverConsultsUserScopedSecrets() {
        service.resolveForCodeTool("PORTAL", "my_tool");

        // A tenant-authored program is invoked by many members; resolving a
        // personal secret here would let its author harvest the caller's.
        verify(repository, never()).findResolvableByName(anyString(), anyString());
    }

    // ------------------------------------------------------ value length

    @Test
    void aValueTooShortToRedactIsRefusedAtTheDoor() {
        // SecretRedactor will not scrub anything under 6 characters, so storing
        // a 4-digit PIN would promise a protection the platform cannot deliver.
        assertThatThrownBy(() -> service.store("DOOR_PIN", SecretScope.TENANT, null,
            SecretKind.CUSTOM, null, "8261", null, false, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least");
    }

    @Test
    void grantingCodeAccessOnAnExistingSecretIsAuditedSeparatelyFromTheRotation() {
        TenantSecret existing = tenantSecret("STRIPE_KEY");
        when(repository.findTenantScoped("STRIPE_KEY")).thenReturn(Optional.of(existing));

        service.store("STRIPE_KEY", SecretScope.TENANT, null, SecretKind.API_KEY,
            null, "sk_live_new_value", null, true, "admin");

        assertThat(existing.isCodeToolAccess()).isTrue();
        verify(auditLogService).record(eq("admin"), eq("SECRET_CODE_ACCESS_GRANTED"), eq("SECRET"),
            anyString(), eq(AuditOutcome.SUCCESS), anyString());
    }
}
