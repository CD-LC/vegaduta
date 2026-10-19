package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.secrets.SecretReferenceResolver;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Defaults (v2 OFF, no domain lists = today's behaviour), clamping, and vault resolution failing
 * closed.
 */
class BrowserUseV2SettingsTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SecretReferenceResolver> provider(
            SecretReferenceResolver resolver) {
        ObjectProvider<SecretReferenceResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return provider;
    }

    private static BrowserUseV2Settings defaults(ObjectProvider<SecretReferenceResolver> resolver) {
        return new BrowserUseV2Settings(
                false, 120, 80, 40, 400, false, 4, 8, false, "", "", "", "", "", resolver);
    }

    @Test
    void shippedDefaultsPreserveTodaysBehaviour() {
        BrowserUseV2Settings settings = defaults(null);
        assertThat(settings.isEnabled()).isFalse();
        assertThat(settings.isLegacyClickTypeEnabled()).isFalse();
        assertThat(settings.maxElements()).isEqualTo(120);
        assertThat(settings.strictStaleness()).isFalse();
        assertThat(settings.domainPolicy().isActive()).isFalse();
        assertThat(settings.secretBindings()).isEmpty();
    }

    @Test
    void numericSettingsAreClampedToSaneBounds() {
        BrowserUseV2Settings settings =
                new BrowserUseV2Settings(
                        true, 100_000, 1, 1, 100_000, false, 4, 8, true, "", "", "", "", "", null);
        assertThat(settings.maxElements()).isEqualTo(400);
        assertThat(settings.nameMaxChars()).isEqualTo(20);
        assertThat(settings.valueMaxChars()).isEqualTo(10);
        assertThat(settings.digestChars()).isEqualTo(2_000);
    }

    @Test
    void domainListsAreParsedFromConfig() {
        UUID agent = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        BrowserUseV2Settings settings =
                new BrowserUseV2Settings(
                        true,
                        120,
                        80,
                        40,
                        400,
                        false,
                        4,
                        8,
                        true,
                        "*.example.com",
                        "ads.example.com",
                        agent + "=a.example.com",
                        "",
                        "PORTAL_PW=login.example.com",
                        null);
        assertThat(settings.domainPolicy().isActive()).isTrue();
        assertThat(settings.domainPolicy().check(agent, "https://a.example.com/").allowed())
                .isTrue();
        assertThat(settings.domainPolicy().check(agent, "https://b.example.com/").allowed())
                .isFalse();
        assertThat(settings.domainPolicy().check(null, "https://ads.example.com/").allowed())
                .isFalse();
        assertThat(settings.secretBindings())
                .containsEntry("PORTAL_PW", List.of("login.example.com"));
    }

    @Test
    void resolvesSecretsFromTheVaultByName() {
        SecretReferenceResolver resolver = mock(SecretReferenceResolver.class);
        when(resolver.resolve("{{secret.PORTAL_PW}}", "user-1"))
                .thenReturn(
                        new SecretReferenceResolver.Resolution(
                                "hunter2-long", Set.of("hunter2-long"), List.of()));
        Map<String, String> values =
                defaults(provider(resolver)).resolveSecrets(Set.of("PORTAL_PW"), "user-1");
        assertThat(values).containsEntry("PORTAL_PW", "hunter2-long");
    }

    @Test
    void anUnresolvableSecretFailsClosedNamingTheSecretNotAValue() {
        SecretReferenceResolver resolver = mock(SecretReferenceResolver.class);
        when(resolver.resolve("{{secret.MISSING}}", "user-1"))
                .thenReturn(
                        new SecretReferenceResolver.Resolution(
                                "{{secret.MISSING}}", Set.of(), List.of("MISSING")));
        assertThatThrownBy(
                        () ->
                                defaults(provider(resolver))
                                        .resolveSecrets(Set.of("MISSING"), "user-1"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("MISSING")
                .hasMessageContaining("could not be resolved");
    }

    @Test
    void noVaultBeanMeansNoSecrets() {
        assertThatThrownBy(() -> defaults(null).resolveSecrets(Set.of("X"), "u"))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> defaults(provider(null)).resolveSecrets(Set.of("X"), "u"))
                .isInstanceOf(BrowserUseException.class);
        // nothing to resolve is fine without a vault
        assertThat(defaults(null).resolveSecrets(Set.of(), "u")).isEmpty();
    }

    @Test
    void newStateUsesTheConfiguredStuckThresholds() {
        BrowserUseV2Settings settings =
                new BrowserUseV2Settings(
                        true, 120, 80, 40, 400, false, 2, 3, true, "", "", "", "", "", null);
        BrowserV2State state = settings.newState();
        assertThat(state.detector().record("a", false).level())
                .isEqualTo(BrowserStuckDetector.Level.OK);
        assertThat(state.detector().record("a", false).level())
                .isEqualTo(BrowserStuckDetector.Level.STUCK);
        assertThat(state.detector().record("a", false).level())
                .isEqualTo(BrowserStuckDetector.Level.ABORT);
        assertThat(state.isAborted()).isTrue();
    }
}
