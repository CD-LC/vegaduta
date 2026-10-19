package com.agenticai.core.omnichannel.matrix;

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
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MatrixCredentialServiceTest {

    private MatrixCredentialRepository repository;
    private EncryptionService encryption;
    private TenantResolutionService resolution;
    private MatrixApiClient api;
    private MatrixCredentialService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(MatrixCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        resolution = mock(TenantResolutionService.class);
        api = mock(MatrixApiClient.class);
        TenantSchemaResolver schema = mock(TenantSchemaResolver.class);
        when(schema.resolveCurrentTenantIdentifier()).thenReturn("tenant_abc");
        when(encryption.encrypt("tok")).thenReturn("enc-tok");
        when(encryption.decrypt("enc-tok")).thenReturn("tok");
        when(repository.save(any(MatrixCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        service =
                new MatrixCredentialService(
                        repository, encryption, resolution, schema, api, mock(ObjectProvider.class));
    }

    @Test
    void activationProvesTheTokenStoresItEncryptedAndLinksTheBotToTheTenant() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(api.whoAmI(any())).thenReturn(new MatrixApiClient.WhoAmI("@bot:hs", "DEV"));

        MatrixCredentialResponse response =
                service.activate(new MatrixActivationRequest("matrix.example.org/", "tok", null, null, null));

        assertThat(response.botUserId()).isEqualTo("@bot:hs");
        assertThat(response.homeserverUrl()).isEqualTo("https://matrix.example.org");
        // Closed by default.
        assertThat(response.joinPolicy()).isEqualTo(MatrixJoinPolicy.OFF);
        assertThat(response.senderMode()).isEqualTo(MatrixSenderMode.PAIRING);
        assertThat(response.toString()).doesNotContain("tok");
        verify(resolution).link(ExternalAccountProvider.MATRIX, "@bot:hs", "tenant_abc");
        org.mockito.ArgumentCaptor<MatrixCredential> saved = org.mockito.ArgumentCaptor.forClass(MatrixCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEncryptedAccessToken()).isEqualTo("enc-tok");
    }

    @Test
    void aBotIdAlreadyOwnedByAnotherWorkspaceIsRefusedAndNeverRelinked() {
        // A hostile homeserver can claim any user id in whoami; it must not take another tenant's bot.
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(api.whoAmI(any())).thenReturn(new MatrixApiClient.WhoAmI("@bot:hs", "DEV"));
        when(resolution.resolveTenantSchema(ExternalAccountProvider.MATRIX, "@bot:hs"))
                .thenReturn(Optional.of("tenant_other"));

        assertThatThrownBy(
                        () -> service.activate(new MatrixActivationRequest("https://hs.example", "tok", null, null, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("another workspace");
        verify(repository, never()).save(any());
        verify(resolution, never()).link(any(), anyString(), anyString());
    }

    @Test
    void reconnectingYourOwnBotIsStillAllowed() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(api.whoAmI(any())).thenReturn(new MatrixApiClient.WhoAmI("@bot:hs", "DEV"));
        when(resolution.resolveTenantSchema(ExternalAccountProvider.MATRIX, "@bot:hs"))
                .thenReturn(Optional.of("tenant_abc"));

        service.activate(new MatrixActivationRequest("https://hs.example", "tok", null, null, null));

        verify(resolution).link(ExternalAccountProvider.MATRIX, "@bot:hs", "tenant_abc");
    }

    @Test
    void aTokenTheHomeserverRejectsIsNeverStored() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(api.whoAmI(any())).thenThrow(new MatrixApiException(401, "M_UNKNOWN_TOKEN", "Unrecognised access token"));

        assertThatThrownBy(
                        () -> service.activate(new MatrixActivationRequest("https://hs.example", "tok", null, null, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("rejected");
        verify(repository, never()).save(any());
        verify(resolution, never()).link(any(), anyString(), anyString());
    }

    @Test
    void replacingTheBotUnlinksTheOldOneAndForgetsTheSyncPosition() {
        MatrixCredential existing =
                new MatrixCredential(
                        "https://old", "@old:hs", "D", "enc", MatrixJoinPolicy.OFF, MatrixSenderMode.PAIRING, "");
        existing.updateSinceToken("s5");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));
        when(api.whoAmI(any())).thenReturn(new MatrixApiClient.WhoAmI("@new:hs", "D2"));

        service.activate(
                new MatrixActivationRequest(
                        "https://new.example", "tok", MatrixJoinPolicy.ALLOWLIST, MatrixSenderMode.ALLOWLIST, "@a:hs"));

        verify(resolution).unlink(ExternalAccountProvider.MATRIX, "@old:hs");
        assertThat(existing.getBotUserId()).isEqualTo("@new:hs");
        assertThat(existing.getSinceToken()).isNull();
        assertThat(existing.getJoinPolicy()).isEqualTo(MatrixJoinPolicy.ALLOWLIST);
    }

    @Test
    void disconnectUnlinksAndKeepsTheRow() {
        MatrixCredential existing =
                new MatrixCredential(
                        "https://hs", "@bot:hs", "D", "enc-tok", MatrixJoinPolicy.OFF, MatrixSenderMode.PAIRING, "");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        service.disconnect();

        assertThat(existing.getStatus()).isEqualTo(MatrixCredentialStatus.DISCONNECTED);
        verify(resolution).unlink(ExternalAccountProvider.MATRIX, "@bot:hs");
        assertThatThrownBy(service::resolveActive).isInstanceOf(MatrixNotConfiguredException.class);
    }

    @Test
    void activeForOnlyServesTheCurrentActiveBot() {
        MatrixCredential existing =
                new MatrixCredential(
                        "https://hs", "@bot:hs", "D", "enc-tok", MatrixJoinPolicy.OFF, MatrixSenderMode.PAIRING, "");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        assertThat(service.activeFor("@bot:hs")).isPresent();
        assertThat(service.activeFor("@bot:hs").get().endpoint().accessToken()).isEqualTo("tok");
        assertThat(service.activeFor("@someone-else:hs")).isEmpty();
        existing.markInvalid();
        assertThat(service.activeFor("@bot:hs")).isEmpty();
    }

    @Test
    void homeserverUrlsAreNormalisedAndHostileOnesRefused() {
        assertThat(MatrixCredentialService.normalizeHomeserver(" https://hs.example/ ")).isEqualTo("https://hs.example");
        assertThat(MatrixCredentialService.normalizeHomeserver("hs.example:8448")).isEqualTo("https://hs.example:8448");
        for (String bad :
                new String[] {
                    "", "ftp://hs.example", "https://user:pw@hs.example", "https://hs.example/?x=1", "https://hs.example/#f"
                }) {
            assertThatThrownBy(() -> MatrixCredentialService.normalizeHomeserver(bad))
                    .as(bad)
                    .isInstanceOf(ChannelConfigurationException.class);
        }
    }

    @Test
    void theActivationRequestNeverPrintsTheToken() {
        assertThat(new MatrixActivationRequest("https://hs", "super-secret", null, null, null).toString())
                .doesNotContain("super-secret");
    }

    @Test
    void updatingThePolicyNeedsAConnection() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.updatePolicy(
                                        new MatrixPolicyRequest(MatrixJoinPolicy.OFF, MatrixSenderMode.PAIRING, "")))
                .isInstanceOf(MatrixNotConfiguredException.class);
        verify(resolution, never()).link(any(), eq("x"), eq("y"));
    }
}
