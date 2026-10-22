package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** The connect/status/disconnect lifecycle over the row, the link and the bridge. */
class WhatsAppQrServiceTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();
    private static final String SCHEMA = "tenant_test";

    private WhatsAppQrConnectionRepository repository;
    private TenantResolutionService resolution;
    private TenantSchemaResolver schemaResolver;
    private WhatsAppQrClient client;
    private WhatsAppQrReceiveService receive;
    private WhatsAppQrService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        repository = mock(WhatsAppQrConnectionRepository.class);
        resolution = mock(TenantResolutionService.class);
        schemaResolver = mock(TenantSchemaResolver.class);
        client = mock(WhatsAppQrClient.class);
        receive = mock(WhatsAppQrReceiveService.class);
        when(schemaResolver.resolveCurrentTenantIdentifier()).thenReturn(SCHEMA);
        when(client.configured()).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ObjectProvider<WhatsAppQrReceiveService> provider = mockProvider(receive);
        service = new WhatsAppQrService(repository, resolution, schemaResolver, client, provider);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** What the transaction manager does once the connecting transaction commits. */
    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WhatsAppQrReceiveService> mockProvider(
            WhatsAppQrReceiveService receive) {
        ObjectProvider<WhatsAppQrReceiveService> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            ((java.util.function.Consumer<WhatsAppQrReceiveService>)
                                            inv.getArgument(0))
                                    .accept(receive);
                            return null;
                        })
                .when(provider)
                .ifAvailable(any());
        return provider;
    }

    @Test
    void connectWithoutABridgeConfiguredIsAConfigurationError() {
        when(client.configured()).thenReturn(false);

        assertThatThrownBy(() -> service.connect())
                .isInstanceOf(ChannelConfigurationException.class)
                .hasMessageContaining("bridge");
    }

    @Test
    void connectCreatesAPairingRowAndLinksTheSessionKey() throws Exception {
        when(client.ensureSession(any()))
                .thenReturn(MAPPER.readTree("{\"status\":\"QR_PENDING\",\"qr\":\"2@qr\"}"));

        WhatsAppQrStatusResponse response = service.connect();

        assertThat(response.status()).isEqualTo("PAIRING");
        assertThat(response.qr()).isEqualTo("2@qr");
        org.mockito.ArgumentCaptor<WhatsAppQrConnection> row =
                org.mockito.ArgumentCaptor.forClass(WhatsAppQrConnection.class);
        verify(repository).save(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(WhatsAppQrStatus.PAIRING);
        verify(resolution)
                .link(
                        eq(ExternalAccountProvider.WHATSAPP_QR),
                        eq(row.getValue().getSessionKey()),
                        eq(SCHEMA));
        // The receiver starts only after the row is committed, so its own thread can read it.
        verify(receive, org.mockito.Mockito.never()).ensureReceiving(any(), any());
        commit();
        verify(receive).ensureReceiving(eq(row.getValue().getSessionKey()), eq(SCHEMA));
    }

    @Test
    void aBridgeConnectFlipsTheRowToActive() throws Exception {
        WhatsAppQrConnection row = new WhatsAppQrConnection("key-1");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(row));
        when(client.sessionStatus("key-1"))
                .thenReturn(
                        MAPPER.readTree(
                                "{\"status\":\"CONNECTED\",\"phoneNumber\":\"4915112345678\"}"));

        WhatsAppQrStatusResponse response = service.status().orElseThrow();

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.phoneNumber()).isEqualTo("4915112345678");
        assertThat(response.qr()).isNull();
    }

    @Test
    void aLoggedOutBridgeSessionMarksTheRowDisconnectedAndUnlinks() throws Exception {
        WhatsAppQrConnection row = new WhatsAppQrConnection("key-2");
        row.markActive("4915");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(row));
        when(client.sessionStatus("key-2"))
                .thenReturn(MAPPER.readTree("{\"status\":\"LOGGED_OUT\"}"));

        WhatsAppQrStatusResponse response = service.status().orElseThrow();

        assertThat(response.status()).isEqualTo("DISCONNECTED");
        verify(resolution).unlink(ExternalAccountProvider.WHATSAPP_QR, "key-2");
    }

    @Test
    void disconnectLogsTheBridgeOutKeepsTheRowAndStopsTheReceiver() {
        WhatsAppQrConnection row = new WhatsAppQrConnection("key-3");
        row.markActive("4915");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(row));

        service.disconnect();

        verify(client).deleteSession("key-3");
        verify(resolution).unlink(ExternalAccountProvider.WHATSAPP_QR, "key-3");
        verify(receive).stopReceiving("key-3");
        assertThat(row.getStatus()).isEqualTo(WhatsAppQrStatus.DISCONNECTED);
    }

    @Test
    void anUnreachableBridgeDoesNotTrapDisconnect() {
        WhatsAppQrConnection row = new WhatsAppQrConnection("key-4");
        row.markActive("4915");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(row));
        org.mockito.Mockito.doThrow(new WhatsAppQrApiException(0, "down"))
                .when(client)
                .deleteSession("key-4");

        service.disconnect();

        assertThat(row.getStatus()).isEqualTo(WhatsAppQrStatus.DISCONNECTED);
    }
}
