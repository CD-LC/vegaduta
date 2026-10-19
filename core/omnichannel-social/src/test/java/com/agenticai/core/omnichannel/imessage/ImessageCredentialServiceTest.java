package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class ImessageCredentialServiceTest {

    private ImessageCredentialRepository repository;
    private EncryptionService encryption;
    private TenantResolutionService tenants;
    private ImessageApiClient apiClient;
    private ImessageCredentialService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ImessageCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        tenants = mock(TenantResolutionService.class);
        apiClient = mock(ImessageApiClient.class);
        TenantSchemaResolver schema = mock(TenantSchemaResolver.class);
        when(schema.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(encryption.decrypt(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replaceAll("^enc\\((.*)\\)$", "$1"));
        when(repository.save(any(ImessageCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        service =
                new ImessageCredentialService(
                        repository, encryption, tenants, schema, apiClient, mock(ObjectProvider.class));
    }

    @Test
    void activateProvesTheServerThenStoresOnlyCiphertextAndLinksTheTenant() {
        ImessageCredentialResponse response =
                service.activate(new ImessageActivationRequest(" http://mac.lan:1234 ", "pw-1", null));

        verify(apiClient).fetchServerInfo("http://mac.lan:1234", "pw-1");
        ArgumentCaptor<ImessageCredential> saved = ArgumentCaptor.forClass(ImessageCredential.class);
        verify(repository).save(saved.capture());
        ImessageCredential c = saved.getValue();
        assertThat(c.getEncryptedPassword()).isEqualTo("enc(pw-1)").doesNotContain("pw-1\"");
        assertThat(c.getEncryptedWebhookSecret()).startsWith("enc(");
        assertThat(c.getInboundMode()).isEqualTo(ImessageInboundMode.WEBHOOK);
        assertThat(c.getUnknownSenderPolicy()).isEqualTo(UnknownSenderPolicy.PAIR);
        assertThat(c.isAllowGroups()).isFalse();
        assertThat(c.allowedSenders()).isEmpty();
        verify(tenants).link(ExternalAccountProvider.IMESSAGE, c.getWebhookId(), "tenant_a");
        assertThat(response.webhookPath()).isEqualTo("/webhook/imessage/" + c.getWebhookId());
        assertThat(response.webhookToken()).hasSize(48).isNotEqualTo(c.getEncryptedWebhookSecret());
        assertThat(response.toString()).doesNotContain("pw-1");
    }

    @Test
    void aServerThatCannotBeProvedIsNeverStored() {
        when(apiClient.fetchServerInfo(anyString(), anyString()))
                .thenThrow(new com.agenticai.core.omnichannel.ChannelConfigurationException("imessage", "nope"));

        assertThatThrownBy(() -> service.activate(new ImessageActivationRequest("http://x", "pw", null)))
                .hasMessageContaining("nope");
        verify(repository, never()).save(any());
        verify(tenants, never()).link(any(), anyString(), anyString());
    }

    @Test
    void reconnectingRotatesTheWebhookIdAndUnlinksTheOldOne() {
        ImessageCredential existing = new ImessageCredential("old-id", "http://old", "enc(a)", "enc(b)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        service.activate(new ImessageActivationRequest("http://new", "pw", null));

        verify(tenants).unlink(ExternalAccountProvider.IMESSAGE, "old-id");
        assertThat(existing.getWebhookId()).isNotEqualTo("old-id");
        assertThat(existing.getServerUrl()).isEqualTo("http://new");
    }

    @Test
    void theWebhookTokenIsComparedAgainstTheDecryptedSecret() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(the-secret)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        assertThat(service.webhookTokenMatches("wid", "the-secret")).isTrue();
        assertThat(service.webhookTokenMatches("wid", "the-secreT")).isFalse();
        assertThat(service.webhookTokenMatches("wid", "")).isFalse();
        assertThat(service.webhookTokenMatches("wid", null)).isFalse();
        assertThat(service.webhookTokenMatches("other-id", "the-secret")).isFalse();
    }

    @Test
    void aDisconnectedCredentialRejectsEverything() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(the-secret)", ImessageInboundMode.WEBHOOK);
        c.disconnect("enc()", "enc(unused)");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        assertThat(service.webhookTokenMatches("wid", "the-secret")).isFalse();
        assertThat(service.inboundSettings()).isEmpty();
        assertThatThrownBy(() -> service.resolveActive()).isInstanceOf(ImessageNotConfiguredException.class);
    }

    @Test
    void disconnectUnlinksTheWebhook() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(s)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        service.disconnect();

        assertThat(c.getStatus()).isEqualTo(ImessageCredentialStatus.DISCONNECTED);
        verify(tenants).unlink(ExternalAccountProvider.IMESSAGE, "wid");
    }

    @Test
    void disconnectDestroysTheStoredPasswordAndSecretAndTheConsoleNoLongerSeesAToken() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(s)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        service.disconnect();

        assertThat(c.getEncryptedPassword()).isEqualTo("enc()");
        assertThat(c.getEncryptedWebhookSecret()).isNotEqualTo("enc(s)");
        assertThat(service.webhookTokenMatches("wid", "s")).isFalse();
        assertThat(service.current().orElseThrow().webhookToken()).isNull();
        assertThat(service.activeServerUrl()).isEmpty();
    }

    @Test
    void theStatusLineNeverDecrypts() {
        ImessageCredential c = new ImessageCredential("wid", "http://mac.lan:1234", "enc(pw)", "enc(s)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        assertThat(service.activeServerUrl()).contains("http://mac.lan:1234");
        verify(encryption, never()).decrypt(anyString());
    }

    @Test
    void settingsAreNormalisedAndJunkIsRejected() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(s)", ImessageInboundMode.WEBHOOK);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        ImessageCredentialResponse response =
                service.updateSettings(
                        new ImessageSettingsRequest(
                                ImessageInboundMode.WEBHOOK,
                                List.of("+1 (555) 010-0100", "Bob@X.com", " "),
                                true,
                                UnknownSenderPolicy.IGNORE));

        assertThat(response.allowedSenders()).containsExactly("+15550100100", "bob@x.com");
        assertThat(response.allowGroups()).isTrue();
        assertThat(response.unknownSenderPolicy()).isEqualTo(UnknownSenderPolicy.IGNORE);

        assertThatThrownBy(
                        () ->
                                service.updateSettings(
                                        new ImessageSettingsRequest(
                                                ImessageInboundMode.WEBHOOK,
                                                List.of("not a number"),
                                                false,
                                                UnknownSenderPolicy.PAIR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
    }

    @Test
    void settingsNeedAConnection() {
        assertThatThrownBy(
                        () ->
                                service.updateSettings(
                                        new ImessageSettingsRequest(
                                                ImessageInboundMode.WEBHOOK, List.of(), false, UnknownSenderPolicy.PAIR)))
                .isInstanceOf(ImessageNotConfiguredException.class);
    }

    @Test
    void switchingModeResetsThePollCursorSoNoHistoryIsReplayed() {
        ImessageCredential c = new ImessageCredential("wid", "http://x", "enc(pw)", "enc(s)", ImessageInboundMode.POLLING);
        c.advancePollCursor(123L);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(c));

        service.updateSettings(new ImessageSettingsRequest(ImessageInboundMode.WEBHOOK, List.of(), false, UnknownSenderPolicy.PAIR));
        assertThat(c.getPollCursorMs()).isNull();
    }

    @Test
    void decryptedCredentialNeverPrintsThePassword() {
        assertThat(new ImessageCredentialService.DecryptedCredential("http://x", "topsecret").toString())
                .doesNotContain("topsecret");
        assertThat(new ImessageActivationRequest("http://x", "topsecret", null).toString()).doesNotContain("topsecret");
    }

    @Test
    void currentReturnsNothingWhenUnconnected() {
        assertThat(service.current()).isEmpty();
        verify(encryption, never()).decrypt(eq("x"));
    }
}
