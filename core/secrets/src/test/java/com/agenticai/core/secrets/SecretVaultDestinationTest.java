package com.agenticai.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.crypto.EncryptionService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SEC-006: the URL-bound plaintext exit ({@link SecretVaultService#resolveForDestination})
 * and the allowed-hosts write path.
 */
class SecretVaultDestinationTest {

    private TenantSecretRepository repository;
    private AuditLogService audit;
    private SecretVaultService service;
    private SecretReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(TenantSecretRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        audit = mock(AuditLogService.class);
        service = new SecretVaultService(repository, encryption, audit);
        resolver = new SecretReferenceResolver(service);
        when(encryption.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(i -> i.getArgument(0).toString().substring(4));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TenantSecret secret(String name, List<String> hosts) {
        TenantSecret s = new TenantSecret(name, SecretScope.TENANT, null, SecretKind.API_KEY,
            null, "enc:the-plaintext-value", "alue", null, false, "admin");
        s.setAllowedHosts(SecretHostPolicy.normalisePatterns(hosts));
        when(repository.findResolvableByName(eq(name), any())).thenReturn(List.of(s));
        return s;
    }

    @Test
    void allowListedDestinationResolves() {
        secret("STRIPE_KEY", List.of("api.stripe.com"));
        assertThat(service.resolveForDestination("STRIPE_KEY", "u1", "api.stripe.com")).contains("the-plaintext-value");
    }

    @Test
    void attackerDestinationDoesNotResolve_andIsAudited() {
        secret("STRIPE_KEY", List.of("api.stripe.com"));
        assertThat(service.resolveForDestination("STRIPE_KEY", "u1", "attacker.tld")).isEmpty();
        verify(audit).record(eq("u1"), eq("SECRET_ACCESSED"), eq("SECRET"), anyString(),
            eq(AuditOutcome.FAILURE), contains("destination not allowed"));
    }

    @Test
    void secretWithoutAllowedHostsNeverResolvesForAUrl_butStillResolvesForMcpEnv() {
        secret("LEGACY", List.of());
        assertThat(service.resolveForDestination("LEGACY", "u1", "api.example.com")).isEmpty();
        // Non-URL carriers (MCP env, integrations) are not affected.
        assertThat(service.resolveForRun("LEGACY", "u1")).contains("the-plaintext-value");
    }

    @Test
    void nullDestinationNeverResolves() {
        secret("STRIPE_KEY", List.of("api.stripe.com"));
        assertThat(service.resolveForDestination("STRIPE_KEY", "u1", null)).isEmpty();
    }

    @Test
    void resolverForDestinationReportsNotAllowedAsUnresolved() {
        secret("STRIPE_KEY", List.of("*.stripe.com"));
        SecretReferenceResolver.Resolution ok =
            resolver.resolveForDestination("Bearer {{secret.STRIPE_KEY}}", "u1", "api.stripe.com");
        assertThat(ok.value()).isEqualTo("Bearer the-plaintext-value");
        SecretReferenceResolver.Resolution denied =
            resolver.resolveForDestination("Bearer {{secret.STRIPE_KEY}}", "u1", "stripe.com.evil.io");
        assertThat(denied.hasUnresolved()).isTrue();
        assertThat(denied.value()).doesNotContain("the-plaintext-value");
        assertThat(denied.resolvedValues()).isEmpty();
    }

    @Test
    void storeAcceptsAndNormalisesAllowedHosts_andRejectsABareWildcard() {
        when(repository.findTenantScoped("NEW_KEY")).thenReturn(Optional.empty());
        TenantSecret stored = service.store("NEW_KEY", SecretScope.TENANT, null, SecretKind.API_KEY, null,
            "value-123456", null, false, List.of("API.Example.com.", "*.stripe.com"), "admin");
        assertThat(stored.getAllowedHosts()).containsExactly("api.example.com", "*.stripe.com");

        assertThatThrownBy(() -> service.store("NEW_KEY", SecretScope.TENANT, null, SecretKind.API_KEY, null,
            "value-123456", null, false, List.of("*"), "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotationWithoutAllowedHostsKeepsTheExistingList() {
        TenantSecret existing = secret("KEEP", List.of("api.example.com"));
        when(repository.findTenantScoped("KEEP")).thenReturn(Optional.of(existing));
        service.store("KEEP", SecretScope.TENANT, null, SecretKind.API_KEY, null,
            "new-value-123456", null, false, "admin");
        assertThat(existing.getAllowedHosts()).containsExactly("api.example.com");
    }

    @Test
    void nonAdminCannotWidenASharedSecretsAllowList() {
        TenantSecret shared = secret("SHARED", List.of("api.example.com"));
        when(repository.findById(shared.getId())).thenReturn(Optional.of(shared));
        assertThatThrownBy(() -> service.updateAllowedHosts(shared.getId(), List.of("attacker.tld"), "u1", false))
            .isInstanceOf(SecretWriteForbiddenException.class);
        assertThat(shared.getAllowedHosts()).containsExactly("api.example.com");

        service.updateAllowedHosts(shared.getId(), List.of("*.example.com"), "admin", true);
        assertThat(shared.getAllowedHosts()).containsExactly("*.example.com");
    }
}
