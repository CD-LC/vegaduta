package com.agenticai.core.providers.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilityController}'s provider resolution on the credential
 * endpoints: once a capability is served by more than one provider, an
 * unqualified DELETE is a 400-class ambiguity, not a coin flip - the request
 * must name {@code provider} and that provider must actually serve the
 * capability.
 */
class CapabilityControllerTest {

    private ProviderCredentialService credentials;
    private CapabilityController controller;

    @BeforeEach
    void setUp() {
        credentials = mock(ProviderCredentialService.class);
        controller = new CapabilityController(
            credentials, mock(CapabilityPolicyService.class), List.of());
        TenantContext.set(TenantContext.of("tenant-1", "subject", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ProviderCredentialValidator validator(String providerKey) {
        ProviderCredentialValidator v = mock(ProviderCredentialValidator.class);
        when(v.providerKey()).thenReturn(providerKey);
        when(v.capabilities()).thenReturn(List.of("DECISION"));
        return v;
    }

    private void twoDecisionProviders() {
        ProviderCredentialValidator typesafe = validator("TYPESAFE");
        ProviderCredentialValidator second = validator("SECOND");
        when(credentials.validators()).thenReturn(Map.of(
            "TYPESAFE", typesafe,
            "SECOND", second));
    }

    @Test
    void deactivateTargetsTheExplicitlyNamedProvider() {
        twoDecisionProviders();

        controller.deactivate("decision", "SECOND");

        verify(credentials).deactivate("SECOND");
    }

    @Test
    void deactivateWithoutProviderIsRejectedWhenMultipleProvidersServe() {
        twoDecisionProviders();

        assertThrows(UnknownCapabilityException.class,
            () -> controller.deactivate("decision", null));
    }

    @Test
    void deactivateWithProviderOutsideTheCapabilityIsRejected() {
        twoDecisionProviders();

        assertThrows(UnknownCapabilityException.class,
            () -> controller.deactivate("decision", "OPENAI"));
    }

    @Test
    void deactivateWithoutProviderStillResolvesTheSoleProvider() {
        ProviderCredentialValidator typesafe = validator("TYPESAFE");
        when(credentials.validators()).thenReturn(Map.of("TYPESAFE", typesafe));

        controller.deactivate("decision", null);

        verify(credentials).deactivate("TYPESAFE");
    }

    @Test
    void credentialStatusAcceptsAnExplicitProvider() {
        twoDecisionProviders();
        when(credentials.findByProviderKey("SECOND"))
            .thenReturn(java.util.Optional.empty());

        assertEquals(204, controller.credentialStatus("decision", "SECOND")
            .getStatusCode().value());
    }
}
