package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SignalCredentialServiceTest {

    private static final String NUMBER = "+4915112345678";
    private static final String URL = "http://signal-cli:8080";

    private SignalCredentialRepository repository;
    private EncryptionService encryption;
    private TenantResolutionService resolution;
    private SignalApiClient api;
    private SignalReceiveService receive;
    private SignalCredentialService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        repository = mock(SignalCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        resolution = mock(TenantResolutionService.class);
        TenantSchemaResolver schema = mock(TenantSchemaResolver.class);
        api = mock(SignalApiClient.class);
        receive = mock(SignalReceiveService.class);
        ObjectProvider<SignalReceiveService> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((java.util.function.Consumer<SignalReceiveService>) inv.getArgument(0)).accept(receive);
            return null;
        }).when(provider).ifAvailable(any());
        when(schema.resolveCurrentTenantIdentifier()).thenReturn("tenant_a");
        when(repository.save(any(SignalCredential.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(resolution.resolveTenantSchema(any(), any())).thenReturn(Optional.empty());
        when(api.about(any())).thenReturn(new SignalApiClient.About("normal", "0.13"));
        when(api.accounts(any())).thenReturn(List.of(NUMBER));
        when(encryption.encrypt("tok")).thenReturn("enc-tok");
        when(encryption.decrypt("enc-tok")).thenReturn("tok");
        service = new SignalCredentialService(repository, encryption, resolution, schema, api, provider);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** What the transaction manager does once the activating transaction commits. */
    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static SignalActivationRequest request(String number, String token) {
        return new SignalActivationRequest(URL + "/", number, token, null, null);
    }

    @Test
    void activatesAfterProvingTheServerAndTheNumberAndNeverReturnsTheToken() {
        SignalCredentialResponse response = service.activate(request("+49 151 1234 5678", "tok"));

        assertThat(response.number()).isEqualTo(NUMBER);
        assertThat(response.baseUrl()).isEqualTo(URL); // trailing slash trimmed
        assertThat(response.hasAuthToken()).isTrue();
        assertThat(response.unknownSenders()).isEqualTo(UnknownSenderPolicy.PAIR);
        assertThat(response.groupMode()).isEqualTo(SignalGroupMode.IGNORE);
        assertThat(response.status()).isEqualTo(SignalCredentialStatus.ACTIVE);
        assertThat(response.toString()).doesNotContain("tok");
        verify(resolution).link(ExternalAccountProvider.SIGNAL, NUMBER, "tenant_a");
        // The receiver starts only after the row is committed, so its own thread can read it.
        verify(receive, never()).ensureReceiving(any(), any());
        commit();
        verify(receive).ensureReceiving(NUMBER, "tenant_a");
    }

    @Test
    void storesTheTokenEncryptedAndResolvesItBack() {
        service.activate(request(NUMBER, "tok"));
        org.mockito.ArgumentCaptor<SignalCredential> saved = org.mockito.ArgumentCaptor.forClass(SignalCredential.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEncryptedAuthToken()).isEqualTo("enc-tok");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(saved.getValue()));

        SignalConnection connection = service.resolveActive();

        assertThat(connection.authToken()).isEqualTo("tok");
        assertThat(connection.accountNumber()).isEqualTo(NUMBER);
        assertThat(connection.toString()).doesNotContain("tok").doesNotContain(NUMBER);
    }

    @Test
    void aBlankTokenMeansNoTokenAtAll() {
        SignalCredentialResponse response = service.activate(request(NUMBER, "   "));

        assertThat(response.hasAuthToken()).isFalse();
        verify(encryption, never()).encrypt(any());
    }

    @Test
    void refusesANumberThatIsNotRegisteredWithThatSignalCli() {
        when(api.accounts(any())).thenReturn(List.of("+4999999999"));

        assertThatThrownBy(() -> service.activate(request(NUMBER, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("no account for this number");
        verify(repository, never()).save(any());
        verify(resolution, never()).link(any(), any(), any());
    }

    @Test
    void reportsAnUnreachableOrRefusedServerAsAConfigurationProblem() {
        when(api.about(any())).thenThrow(new SignalApiException(SignalApiException.REFUSED, "refused by policy"));

        assertThatThrownBy(() -> service.activate(request(NUMBER, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("refused by policy");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsABadUrlOrNumberBeforeAnyCall() {
        assertThatThrownBy(() -> service.activate(new SignalActivationRequest("ftp://x", NUMBER, null, null, null)))
                .isInstanceOf(ChannelConfigurationException.class);
        assertThatThrownBy(() -> service.activate(new SignalActivationRequest(URL + "?a=b", NUMBER, null, null, null)))
                .isInstanceOf(ChannelConfigurationException.class);
        assertThatThrownBy(() -> service.activate(request("12345", null)))
                .isInstanceOf(ChannelConfigurationException.class);
        verify(api, never()).about(any());
    }

    @Test
    void refusesCredentialsInTheUrlAndHeaderBreakingTokens() {
        assertThatThrownBy(() -> service.activate(
                        new SignalActivationRequest("http://user:secret@signal-cli:8080", NUMBER, null, null, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("user name or password");
        assertThatThrownBy(() -> service.activate(request(NUMBER, "tok\r\nX-Evil: 1")))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("printable ASCII");
        verify(api, never()).about(any());
        verify(repository, never()).save(any());
    }

    @Test
    void aNumberAlreadyConnectedByAnotherWorkspaceIsNotStolen() {
        when(resolution.resolveTenantSchema(ExternalAccountProvider.SIGNAL, NUMBER)).thenReturn(Optional.of("tenant_b"));

        assertThatThrownBy(() -> service.activate(request(NUMBER, null)))
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("another workspace");
        verify(resolution, never()).link(any(), any(), any());
    }

    @Test
    void reconnectingWithTheSameNumberInTheSameWorkspaceIsFine() {
        when(resolution.resolveTenantSchema(ExternalAccountProvider.SIGNAL, NUMBER)).thenReturn(Optional.of("tenant_a"));

        assertThat(service.activate(request(NUMBER, null)).status()).isEqualTo(SignalCredentialStatus.ACTIVE);
    }

    @Test
    void switchingToANewNumberUnlinksAndStopsTheOldReceiver() {
        SignalCredential existing = new SignalCredential(URL, "+4900000000001", null, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        service.activate(request(NUMBER, null));

        verify(resolution).unlink(ExternalAccountProvider.SIGNAL, "+4900000000001");
        verify(receive).stopReceiving("+4900000000001");
        assertThat(existing.getAccountNumber()).isEqualTo(NUMBER);
    }

    @Test
    void disconnectMarksTheRowUnlinksAndStopsReceivingWithoutTouchingSignalCli() {
        SignalCredential existing = new SignalCredential(URL, NUMBER, null, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        service.disconnect();

        assertThat(existing.getStatus()).isEqualTo(SignalCredentialStatus.DISCONNECTED);
        verify(resolution).unlink(ExternalAccountProvider.SIGNAL, NUMBER);
        verify(receive).stopReceiving(NUMBER);
        verify(api, never()).send(any(), any(), any(), any());
        assertThatThrownBy(() -> service.resolveActive()).isInstanceOf(SignalNotConfiguredException.class);
    }

    @Test
    void disconnectWithNothingConnectedIsANoOp() {
        service.disconnect();

        verify(resolution, never()).unlink(any(), any());
    }

    @Test
    void policyChangesApplyWithoutReEnteringTheConnection() {
        SignalCredential existing = new SignalCredential(URL, NUMBER, null, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        SignalCredentialResponse response =
                service.updatePolicy(new SignalPolicyRequest(UnknownSenderPolicy.IGNORE, null));

        assertThat(response.unknownSenders()).isEqualTo(UnknownSenderPolicy.IGNORE);
        assertThat(response.groupMode()).isEqualTo(SignalGroupMode.IGNORE); // null keeps the stored value
        verify(api, never()).about(any());
    }

    @Test
    void policyCannotBeChangedWhileNothingIsConnected() {
        assertThatThrownBy(() -> service.updatePolicy(new SignalPolicyRequest(null, SignalGroupMode.ALL)))
                .isInstanceOf(SignalNotConfiguredException.class);
    }

    @Test
    void currentIsEmptyWhenNothingIsConnected() {
        assertThat(service.current()).isEmpty();
        verify(resolution, never()).link(eq(ExternalAccountProvider.SIGNAL), any(), any());
    }
}
