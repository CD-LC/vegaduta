package com.agenticai.core.agents.tools.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.agenticai.core.security.net.SsrfGuard;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * SEC-006 on the OpenAPI tool path: a vault reference inside an OpenAPI source's stored credential
 * resolves only for the host the credential is sent to. A source pointed at an attacker host cannot
 * pull a workspace secret that is allow-listed for another API.
 */
class OpenApiSecretDestinationTest {

    private final SecretVaultService vault = mock(SecretVaultService.class);
    private OpenApiToolExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.decrypt("ciphertext")).thenReturn("Bearer {{secret.STRIPE_KEY}}");
        ObjectProvider<EncryptionService> encryptionProvider = mock(ObjectProvider.class);
        when(encryptionProvider.getIfAvailable()).thenReturn(encryption);
        ObjectProvider<SecretReferenceResolver> resolverProvider = mock(ObjectProvider.class);
        when(resolverProvider.getIfAvailable()).thenReturn(new SecretReferenceResolver(vault));
        when(vault.resolveForDestination(eq("STRIPE_KEY"), isNull(), eq("api.stripe.com")))
                .thenReturn(Optional.of("sk_live_123"));
        when(vault.resolveForDestination(eq("STRIPE_KEY"), isNull(), eq("attacker.example")))
                .thenReturn(Optional.empty());
        executor =
                new OpenApiToolExecutor(encryptionProvider, resolverProvider, SsrfGuard.strict());
    }

    private static OpenApiToolSource apiKeySource(String baseUrl) {
        return new OpenApiToolSource(
                "Stripe",
                "stripe",
                null,
                "{}",
                "Stripe",
                "1.0.0",
                baseUrl,
                OpenApiAuthType.API_KEY_HEADER,
                "Authorization",
                null,
                null,
                null,
                null,
                "ciphertext",
                "...",
                "tester");
    }

    @Test
    void referenceResolvesForTheAllowListedDestination() {
        assertThat(executor.resolveSecret(apiKeySource("https://api.stripe.com"), "api.stripe.com"))
                .isEqualTo("Bearer sk_live_123");
    }

    @Test
    void referenceIsRefusedForAnyOtherDestinationWithAGenericMessage() {
        assertThatThrownBy(
                        () ->
                                executor.resolveSecret(
                                        apiKeySource("https://attacker.example"),
                                        "attacker.example"))
                .isInstanceOf(OpenApiToolException.class)
                .hasMessageContaining("not available for this destination")
                .hasMessageNotContaining("STRIPE_KEY");
    }

    @Test
    void noDestinationHostMeansNoResolution() {
        assertThatThrownBy(
                        () -> executor.resolveSecret(apiKeySource("https://api.stripe.com"), null))
                .isInstanceOf(OpenApiToolException.class);
        verify(vault, never()).resolveForRun(anyString(), org.mockito.ArgumentMatchers.any());
    }
}
