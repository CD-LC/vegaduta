package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** The receiver against a fake signal-cli: long polling, the json-rpc websocket, tenant scoping and recovery. */
class SignalReceiveServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NUMBER = "+4915112345678";
    private static final String ONE =
            "{\"envelope\":{\"sourceNumber\":\"+4917600000001\",\"timestamp\":1,\"dataMessage\":{\"timestamp\":1,\"message\":\"one\"}}}";
    private static final String TWO =
            "{\"envelope\":{\"sourceNumber\":\"+4917600000001\",\"timestamp\":2,\"dataMessage\":{\"timestamp\":2,\"message\":\"two\"}}}";

    private FakeSignalCli cli;
    private SignalCredentialRepository repository;
    private SignalInboundService inbound;
    private SignalReceiveService service;
    private final List<String> tenantsSeen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        cli = new FakeSignalCli();
        repository = mock(SignalCredentialRepository.class);
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.decrypt("enc")).thenReturn("tok");
        SignalCredential credential =
                new SignalCredential(cli.baseUrl(), NUMBER, null, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        inbound = mock(SignalInboundService.class);
        when(inbound.accept(any(), any())).thenAnswer(inv -> {
            tenantsSeen.add(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            return true;
        });
    }

    private void newService(String mode) {
        SignalApiClient api = new SignalApiClient(MAPPER, cli::guard);
        @SuppressWarnings("unchecked")
        ObjectProvider<SignalReceiveService> self = mock(ObjectProvider.class);
        SignalCredentialService credentials = new SignalCredentialService(
                repository,
                mock(EncryptionService.class),
                mock(TenantResolutionService.class),
                mock(TenantSchemaResolver.class),
                api,
                self);
        service = new SignalReceiveService(
                mock(TenantResolutionService.class), repository, credentials, api, inbound, mode, 1);
    }

    @AfterEach
    void stop() throws Exception {
        if (service != null) {
            service.shutdown();
        }
        cli.close();
    }

    @Test
    void longPollingDeliversMessagesInTheOwningTenant() {
        cli.mode = "normal";
        cli.receiveBatches.add("[" + ONE + "," + TWO + "]");
        newService("auto");

        service.ensureReceiving(NUMBER, "tenant_abc");

        ArgumentCaptor<JsonNode> messages = ArgumentCaptor.forClass(JsonNode.class);
        verify(inbound, timeout(5000).atLeast(2)).accept(any(), messages.capture());
        assertThat(messages.getAllValues().get(0).path("envelope").path("dataMessage").path("message").asText())
                .isEqualTo("one");
        assertThat(messages.getAllValues().get(1).path("envelope").path("dataMessage").path("message").asText())
                .isEqualTo("two");
        assertThat(tenantsSeen).containsOnly("tenant_abc");
        assertThat(service.problem(NUMBER)).isNull();
    }

    @Test
    void jsonRpcModeIsDetectedAndReceivedOverTheWebsocket() throws Exception {
        cli.mode = "json-rpc";
        newService("auto");

        service.ensureReceiving(NUMBER, "tenant_ws");
        waitFor(() -> cli.requestLines.stream().anyMatch(l -> l.contains("/v1/receive/")));
        cli.wsFrames.add(ONE);
        cli.wsFrames.add(TWO);

        verify(inbound, timeout(5000).atLeast(2)).accept(any(), any());
        assertThat(tenantsSeen).containsOnly("tenant_ws");
        assertThat(cli.requestLines).anyMatch(l -> l.contains("/v1/about"));
    }

    @Test
    void aConfiguredModeSkipsTheServerProbe() throws Exception {
        cli.mode = "json-rpc"; // the server says websocket, the operator forces polling
        cli.receiveBatches.add("[" + ONE + "]");
        newService("poll");

        service.ensureReceiving(NUMBER, "tenant_abc");

        verify(inbound, timeout(5000)).accept(any(), any());
        assertThat(cli.requestLines).noneMatch(l -> l.contains("/v1/about"));
    }

    @Test
    void reconnectsAfterTheWebsocketDropsAndKeepsReceiving() throws Exception {
        cli.mode = "json-rpc";
        newService("websocket");
        service.ensureReceiving(NUMBER, "tenant_ws");
        waitFor(() -> cli.requestLines.stream().anyMatch(l -> l.contains("/v1/receive/")));

        cli.dropConnections(); // a signal-cli restart
        // Backoff is one second; the next connection must carry a frame again.
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && cli.requestLines.stream().filter(l -> l.contains("/v1/receive/")).count() < 2) {
            Thread.sleep(50);
        }
        cli.wsFrames.add(ONE);

        verify(inbound, timeout(8000)).accept(any(), any());
    }

    @Test
    void aHealthyConnectionThatDropsAgainIsANewIncidentNotMoreBackoff() throws Exception {
        cli.mode = "json-rpc";
        newService("websocket");
        service.ensureReceiving(NUMBER, "tenant_ws");

        // Four drops, each after a good connection. If failures piled up across them the waits
        // would be 2 + 4 + 8 + 16 s and the receiver would report signal-cli as unreachable.
        long started = System.currentTimeMillis();
        for (int connections = 1; connections <= 4; connections++) {
            final int expected = connections;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline
                    && cli.requestLines.stream().filter(l -> l.contains("/v1/receive/")).count() < expected) {
                Thread.sleep(50);
            }
            assertThat(cli.requestLines.stream().filter(l -> l.contains("/v1/receive/")).count())
                    .isGreaterThanOrEqualTo(expected);
            Thread.sleep(300); // let the stream settle as "up"
            cli.dropConnections();
        }

        assertThat(System.currentTimeMillis() - started).isLessThan(14_000);
        assertThat(service.problem(NUMBER)).isNull();
    }

    @Test
    void aMessageThatCannotBeHandledDoesNotStopTheReceiver() {
        cli.receiveBatches.add("[" + ONE + "," + TWO + "]");
        AtomicInteger calls = new AtomicInteger();
        when(inbound.accept(any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("no default agent configured");
            }
            return true;
        });
        newService("poll");

        service.ensureReceiving(NUMBER, "tenant_abc");

        verify(inbound, timeout(5000).times(2)).accept(any(), any());
    }

    @Test
    void anUnreachableServerBacksOffAndIsReportedAsAProblem() throws Exception {
        cli.close();
        newService("poll");

        service.ensureReceiving(NUMBER, "tenant_abc");

        long deadline = System.currentTimeMillis() + 15_000;
        while (service.problem(NUMBER) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(service.problem(NUMBER)).contains("signal-cli not reachable");
    }

    @Test
    void stopsWhenTheConnectionIsNoLongerActive() throws Exception {
        SignalCredential disconnected =
                new SignalCredential(cli.baseUrl(), NUMBER, null, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
        disconnected.disconnect();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(disconnected));
        newService("poll");

        service.ensureReceiving(NUMBER, "tenant_abc");
        Thread.sleep(500);

        assertThat(cli.requestLines).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(inbound);
    }

    @Test
    void startAllRestoresEveryLinkedNumberAndSurvivesALookupFailure() {
        cli.receiveBatches.add("[" + ONE + "]");
        TenantResolutionService resolution = mock(TenantResolutionService.class);
        when(resolution.listAccounts(ExternalAccountProvider.SIGNAL))
                .thenReturn(List.of(new TenantResolutionService.AccountTenant(NUMBER, "tenant_restored")));
        SignalApiClient api = new SignalApiClient(MAPPER, cli::guard);
        @SuppressWarnings("unchecked")
        ObjectProvider<SignalReceiveService> self = mock(ObjectProvider.class);
        SignalCredentialService credentials = new SignalCredentialService(
                repository, mock(EncryptionService.class), resolution, mock(TenantSchemaResolver.class), api, self);
        service = new SignalReceiveService(resolution, repository, credentials, api, inbound, "poll", 1);

        service.startAll();

        verify(inbound, timeout(5000)).accept(any(), any());
        assertThat(tenantsSeen).contains("tenant_restored");

        TenantResolutionService broken = mock(TenantResolutionService.class);
        when(broken.listAccounts(ExternalAccountProvider.SIGNAL)).thenThrow(new IllegalStateException("db down"));
        new SignalReceiveService(broken, repository, credentials, api, inbound, "poll", 1).startAll(); // must not throw
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
