package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.validation.ProviderValidator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import com.agenticai.core.settings.SettingsResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * A workspace's {@code providers.credential.rejectedCooldown} reaches the real credential resolver and the real
 * providers listing: how long a refused key is skipped by background callers, and the "paused until" it reports.
 * With nothing stored the six hours the property has always given apply.
 */
class ProviderSettingsTest {

    private final LlmProviderCredentialRepository repository = mock(LlmProviderCredentialRepository.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final InMemorySettingsStore store = new InMemorySettingsStore();
    private final MockEnvironment env = new MockEnvironment();
    private final DefaultSettingsResolver resolver = new DefaultSettingsResolver(
            new SettingsRegistry(List.of(new ProviderSettings(env))), store, List.of(), new SettingsProperties(), null);

    @BeforeEach
    void tenant() {
        TenantContext.set(TenantContext.of("acme", "alice", Set.of("ROLE_tenant-admin")));
        LlmProviderCredential credential = mock(LlmProviderCredential.class);
        when(credential.getProviderName()).thenReturn(ProviderName.GOOGLE_VERTEX);
        when(credential.getEncryptedApiKey()).thenReturn("cipher");
        when(repository.findByStatusOrderByCreatedAtAsc(ProviderStatus.ACTIVE)).thenReturn(List.of(credential));
        when(encryption.decrypt("cipher")).thenReturn("plain");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void overrideSeconds(long seconds) {
        store.saveCurrent(new SettingOverride(
                SettingScope.TENANT, "", ProviderSettings.REJECTED_COOLDOWN, seconds, 1, "alice", Instant.now()));
    }

    private ProviderCredentialResolver credentials(long refusedSecondsAgo, SettingsResolver settings) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> provider = mock(ObjectProvider.class);
        Map<ProviderName, ProviderUsageSignal.Usage> usage = Map.of(ProviderName.GOOGLE_VERTEX,
                new ProviderUsageSignal.Usage(null, null, Instant.now().minusSeconds(refusedSecondsAgo), true));
        when(provider.getIfAvailable()).thenReturn(providers -> usage);
        ProviderCredentialResolver credentials = new ProviderCredentialResolver(repository, encryption, List.of(), provider);
        credentials.setSettings(settings);
        return credentials;
    }

    @Test
    void theCatalogDefaultIsTheSixHoursThePropertyGives() {
        assertThat(resolver.getDuration(ProviderSettings.REJECTED_COOLDOWN, SettingContext.tenant())).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void thePlatformValueFollowsTheDeploymentProperty() {
        env.setProperty("app.providers.rejected-cooldown-hours", "2");
        assertThat(resolver.getDuration(ProviderSettings.REJECTED_COOLDOWN, SettingContext.tenant())).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void withNothingOverriddenAKeyRefusedThreeHoursAgoIsStillSkipped() {
        assertThatThrownBy(() -> credentials(3 * 3600, resolver).resolveBest(true))
                .isInstanceOf(ProviderCoolingDownException.class);
    }

    @Test
    void aShorterWorkspaceCooldownLetsAProbeThroughSooner() {
        overrideSeconds(3600);
        assertThat(credentials(3 * 3600, resolver).resolveBest(true).providerName()).isEqualTo(ProviderName.GOOGLE_VERTEX);
    }

    @Test
    void aLongerWorkspaceCooldownKeepsTheKeyPausedPastTheDefault() {
        overrideSeconds(24 * 3600);
        assertThatThrownBy(() -> credentials(7 * 3600, resolver).resolveBest(true))
                .isInstanceOf(ProviderCoolingDownException.class);
    }

    @Test
    void aZeroCooldownNeverPausesAKey() {
        overrideSeconds(0);
        assertThat(credentials(60, resolver).resolveBest(true).providerName()).isEqualTo(ProviderName.GOOGLE_VERTEX);
    }

    @Test
    void thePersonDrivenPathIsNotAffectedByTheCooldown() {
        overrideSeconds(24 * 3600);
        assertThat(credentials(60, resolver).resolveBest(false).credentialSuspect()).isTrue();
    }

    @Test
    void anotherWorkspacesOverrideNeverReachesThisOne() {
        overrideSeconds(0);
        TenantContext.set(TenantContext.of("globex", "bob", Set.of()));
        assertThatThrownBy(() -> credentials(60, resolver).resolveBest(true)).isInstanceOf(ProviderCoolingDownException.class);
    }

    @Test
    void withoutAResolverThePropertyFieldApplies() {
        overrideSeconds(0);
        assertThatThrownBy(() -> credentials(60, null).resolveBest(true)).isInstanceOf(ProviderCoolingDownException.class);
    }

    @Test
    void aResolverThatFailsFallsBackToThePropertyAndTheCallStillWorks() {
        SettingsResolver broken = mock(SettingsResolver.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new IllegalArgumentException("unknown key")).when(broken).get(any(), any());
        assertThatThrownBy(() -> credentials(60, broken).resolveBest(true)).isInstanceOf(ProviderCoolingDownException.class);
    }

    @Test
    void theProvidersListingReportsTheWorkspacesCooldown() {
        overrideSeconds(2 * 3600);
        ProviderUsageSignal signal = mock(ProviderUsageSignal.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProviderUsageSignal> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(signal);
        LlmProviderCredential vertex = mock(LlmProviderCredential.class);
        when(vertex.getProviderName()).thenReturn(ProviderName.GOOGLE_VERTEX);
        when(vertex.getStatus()).thenReturn(ProviderStatus.ACTIVE);
        when(repository.findAll()).thenReturn(List.of(vertex));
        ProviderValidator validator = mock(ProviderValidator.class);
        when(validator.supportedProvider()).thenReturn(ProviderName.ANTHROPIC);
        ProviderService service = new ProviderService(repository, encryption, List.of(validator), List.of(),
                mock(TenantLimits.class), mock(PlatformHostedLlmProviderService.class), provider);
        service.setSettings(resolver);

        service.listAll();

        ArgumentCaptor<Duration> cooldown = ArgumentCaptor.forClass(Duration.class);
        verify(signal).health(any(), cooldown.capture());
        assertThat(cooldown.getValue()).isEqualTo(Duration.ofHours(2));
    }
}
