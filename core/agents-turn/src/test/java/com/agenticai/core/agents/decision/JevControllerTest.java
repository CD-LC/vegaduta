package com.agenticai.core.agents.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.decision.dto.JevCredentialActivationRequest;
import com.agenticai.core.providers.ProviderStatus;
import com.agenticai.core.providers.capability.CapabilityPolicyService;
import com.agenticai.core.providers.capability.ProviderCredential;
import com.agenticai.core.providers.capability.ProviderCredentialService;
import com.agenticai.core.providers.capability.ProviderKeys;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@link JevController} tenant-context guard coverage. The {@code
 * provider_credential}/{@code capability_policy} tables live in the tenant
 * schema (V217/V352), so every repository touch must run under a
 * TenantContext - a tenant-less caller (platform-admin JWTs never carry
 * tenant_id) used to fall through to the public-schema connection where the
 * table does not exist and surface as an opaque 500. The guard turns that
 * into a clean 400 on writes and a truthful "nothing here" on reads, and
 * these tests pin the repository-is-never-touched contract.
 */
class JevControllerTest {

    private ProviderCredentialService credentials;
    private DecisionEngine decisions;
    private CapabilityPolicyService policies;
    private JevController controller;

    @BeforeEach
    void setUp() {
        credentials = mock(ProviderCredentialService.class);
        decisions = mock(DecisionEngine.class);
        policies = mock(CapabilityPolicyService.class);
        controller = new JevController(credentials, decisions, policies);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void statusWithoutTenantContextDoesNotQueryCredentialStore() {
        when(decisions.isEnabled()).thenReturn(true);
        when(decisions.isAvailable()).thenReturn(true);
        when(decisions.keySource()).thenReturn(Optional.of(DecisionModel.KeySource.PLATFORM));

        var response = controller.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tenantCredential()).isNull();
        // The whole point: no repository call on the public schema.
        verifyNoInteractions(credentials);
    }

    @Test
    void credentialStatusWithoutTenantContextReturns204() {
        var response = controller.credentialStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verifyNoInteractions(credentials);
    }

    @Test
    void activateWithoutTenantContextIsA400NotASchemaMiss() {
        assertThatThrownBy(() -> controller.activate(new JevCredentialActivationRequest("ts-key")))
            .isInstanceOf(JevNotConfiguredException.class);
        verifyNoInteractions(credentials);
    }

    @Test
    void deactivateWithoutTenantContextIsA400NotASchemaMiss() {
        assertThatThrownBy(() -> controller.deactivate())
            .isInstanceOf(JevNotConfiguredException.class);
        verifyNoInteractions(credentials);
    }

    @Test
    void tenantContextReadsTheCredentialStore() {
        TenantContext.set(TenantContext.of("tenant-1", "user", Set.of()));
        ProviderCredential row = new ProviderCredential(
            ProviderKeys.TYPESAFE, List.of("DECISION"), "cipher", ProviderStatus.ACTIVE, null);
        when(credentials.findByProviderKey(ProviderKeys.TYPESAFE)).thenReturn(Optional.of(row));

        var response = controller.credentialStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(ProviderStatus.ACTIVE);
        assertThat(response.getBody().createdAt()).isEqualTo(row.getCreatedAt());
    }
}
