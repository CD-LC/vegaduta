package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantResolutionService.AccountTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The receive loop's lifecycle: start-up, stop conditions, bridge errors and message delivery. */
class WhatsAppQrReceiveServiceTest {

    private static final String KEY = "abcdef12-session";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TenantResolutionService tenants;
    private WhatsAppQrConnectionRepository connections;
    private WhatsAppQrService service;
    private WhatsAppQrClient client;
    private WhatsAppQrInboundService inbound;
    private WhatsAppQrReceiveService receive;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantResolutionService.class);
        connections = mock(WhatsAppQrConnectionRepository.class);
        service = mock(WhatsAppQrService.class);
        client = mock(WhatsAppQrClient.class);
        inbound = mock(WhatsAppQrInboundService.class);
        receive = new WhatsAppQrReceiveService(tenants, connections, service, client, inbound, 99);
        when(client.configured()).thenReturn(true);
        WhatsAppQrConnection alive = mock(WhatsAppQrConnection.class);
        when(alive.getStatus()).thenReturn(WhatsAppQrStatus.ACTIVE);
        when(connections.findBySessionKey(KEY)).thenReturn(Optional.of(alive));
    }

    @AfterEach
    void tearDown() {
        receive.stopReceiving(KEY);
        receive.shutdown();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void anUnconfiguredBridgeDoesNothingAtStartAndRecordsAProblemOnDemand() {
        when(client.configured()).thenReturn(false);

        receive.startAll();
        receive.ensureReceiving(KEY, "acme");

        verify(tenants, never()).listAccounts(any());
        assertThat(receive.problem(KEY)).isEqualTo(WhatsAppQrReceiveService.NOT_CONFIGURED);
        receive.stopReceiving(KEY);
        assertThat(receive.problem(KEY)).isNull();
    }

    @Test
    void startAllRestoresEveryLinkedAccountAndSurvivesALookupFailure() throws Exception {
        when(tenants.listAccounts(ExternalAccountProvider.WHATSAPP_QR))
                .thenReturn(List.of(new AccountTenant(KEY, "acme")));
        when(connections.findBySessionKey(KEY)).thenReturn(Optional.empty());

        receive.startAll();

        verify(connections, timeout(5000).atLeastOnce()).findBySessionKey(KEY);

        when(tenants.listAccounts(any())).thenThrow(new IllegalStateException("db down"));
        receive.startAll();
    }

    @Test
    void aGoneConnectionStopsTheLoopWithoutPolling() throws Exception {
        when(connections.findBySessionKey(KEY)).thenReturn(Optional.empty());

        receive.ensureReceiving(KEY, "acme");

        verify(connections, timeout(5000).atLeastOnce()).findBySessionKey(KEY);
        verify(client, never()).receive(anyString(), anyInt());
    }

    @Test
    void aDisconnectedRowCountsAsGone() throws Exception {
        WhatsAppQrConnection off = mock(WhatsAppQrConnection.class);
        when(off.getStatus()).thenReturn(WhatsAppQrStatus.DISCONNECTED);
        when(connections.findBySessionKey(KEY)).thenReturn(Optional.of(off));

        receive.ensureReceiving(KEY, "acme");

        verify(connections, timeout(5000).atLeastOnce()).findBySessionKey(KEY);
        verify(client, never()).receive(anyString(), anyInt());
    }

    @Test
    void theBridgePollIntervalIsClampedToTwentySeconds() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenReturn(json("{\"status\":\"LOGGED_OUT\",\"events\":[{\"id\":1}]}"));
        when(inbound.accept(any())).thenReturn(true);

        receive.ensureReceiving(KEY, "acme");

        verify(client, timeout(5000)).receive(KEY, 20);
        verify(service, timeout(5000)).markLoggedOut(KEY);
        verify(inbound).accept(any());
    }

    @Test
    void aConnectedStatusIsRecordedAndALoggedOutOneEndsTheLoop() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenReturn(
                        json(
                                "{\"status\":\"CONNECTED\",\"phoneNumber\":\"+9198\",\"events\":[{\"id\":1}]}"))
                .thenReturn(json("{\"status\":\"LOGGED_OUT\",\"events\":[{\"id\":2}]}"));
        when(inbound.accept(any())).thenReturn(true);

        receive.ensureReceiving(KEY, "acme");

        verify(service, timeout(5000)).markConnected(KEY, "+9198");
        verify(service, timeout(5000)).markLoggedOut(KEY);
    }

    @Test
    void anUnknownSessionOnTheBridgeIsRecreatedAndPolledAgain() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenThrow(new WhatsAppQrApiException(404, "no such session"))
                .thenReturn(json("{\"status\":\"LOGGED_OUT\",\"events\":[{\"id\":1}]}"));
        when(inbound.accept(any())).thenReturn(true);

        receive.ensureReceiving(KEY, "acme");

        verify(client, timeout(5000)).ensureSession(KEY);
        verify(service, timeout(5000)).markLoggedOut(KEY);
    }

    @Test
    void aRefusedRequestStopsReceivingAndExplainsWhy() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenThrow(
                        new WhatsAppQrApiException(
                                WhatsAppQrApiException.REFUSED, "blocked by guard"));

        receive.ensureReceiving(KEY, "acme");

        await(() -> receive.problem(KEY) != null);
        assertThat(receive.problem(KEY)).contains("bridge refused").contains("blocked by guard");
    }

    @Test
    void anUnreachableBridgeIsRetriedUntilStopped() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenThrow(new IllegalStateException("x".repeat(300)));

        receive.ensureReceiving(KEY, "acme");

        verify(client, timeout(5000).atLeastOnce()).receive(eq(KEY), anyInt());
        receive.stopReceiving(KEY);
        assertThat(receive.problem(KEY)).isNull();
    }

    @Test
    void ensuringTwiceKeepsASingleRunningReceiver() throws Exception {
        when(client.receive(eq(KEY), anyInt())).thenThrow(new IllegalStateException("down"));

        receive.ensureReceiving(KEY, "acme");
        receive.ensureReceiving(KEY, "acme");

        verify(client, timeout(5000).atLeastOnce()).receive(eq(KEY), anyInt());
        verify(connections, atLeastOnce()).findBySessionKey(KEY);
    }

    @Test
    void aMessageThatFailsToProcessIsLoggedAndSkipped() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenReturn(json("{\"status\":\"LOGGED_OUT\",\"events\":[{\"id\":1}]}"));
        when(inbound.accept(any())).thenThrow(new IllegalStateException("parse error"));

        receive.ensureReceiving(KEY, "acme");

        verify(service, timeout(5000)).markLoggedOut(KEY);
        verify(inbound).accept(any());
    }

    @Test
    void aFullInboundExecutorIsRetriedAfterABackoff() throws Exception {
        when(client.receive(eq(KEY), anyInt()))
                .thenReturn(json("{\"status\":\"LOGGED_OUT\",\"events\":[{\"id\":1}]}"));
        when(inbound.accept(any())).thenReturn(false).thenReturn(true);

        receive.ensureReceiving(KEY, "acme");

        verify(service, timeout(8000)).markLoggedOut(KEY);
        verify(inbound, org.mockito.Mockito.times(2)).accept(any());
        verify(client, never()).ensureSession(isNull());
    }
}
