package com.agenticai.core.omnichannel.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A bot with no public URL receives messages by long polling, in its own tenant. */
class TelegramPollingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> getUpdatesBodies = new CopyOnWriteArrayList<>();
    private TelegramPollingService service;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    calls.add(path.substring(path.lastIndexOf('/') + 1));
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    String result = "true";
                    if (path.endsWith("/getUpdates")) {
                        getUpdatesBodies.add(body);
                        // First poll: one message. Later polls: nothing (after a short wait).
                        if (getUpdatesBodies.size() == 1) {
                            result =
                                    "[{\"update_id\":7,\"message\":{\"chat\":{\"id\":1},\"from\":{\"id\":2},\"text\":\"hi\"}}]";
                        } else {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ignored) {
                                // shutting down
                            }
                            result = "[]";
                        }
                    }
                    byte[] bytes = ("{\"ok\":true,\"result\":" + result + "}").getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        if (service != null) {
            service.shutdown();
        }
        server.stop(0);
    }

    @Test
    void deliversAMessageToTheTenantAndAcknowledgesIt() throws Exception {
        TelegramCredentialRepository repository = mock(TelegramCredentialRepository.class);
        TelegramCredential credential = new TelegramCredential("42", "bot", "enc", "secret");
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(credential));
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.decrypt("enc")).thenReturn("tok");
        TelegramUpdateService handler = mock(TelegramUpdateService.class);
        AtomicReference<String> tenantSeen = new AtomicReference<>();
        when(handler.acceptVerifiedUpdate(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            tenantSeen.set(
                                    TenantContext.current()
                                            .map(TenantContext::getTenantId)
                                            .orElse(null));
                            return true;
                        });
        TelegramApiClient api =
                new TelegramApiClient(
                        MAPPER, "http://127.0.0.1:" + server.getAddress().getPort() + "/bot",
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        service =
                new TelegramPollingService(
                        mock(TenantResolutionService.class),
                        repository,
                        encryption,
                        api,
                        handler,
                        "");

        service.ensurePolling("42", "tenant_abc");

        ArgumentCaptor<JsonNode> update = ArgumentCaptor.forClass(JsonNode.class);
        verify(handler, timeout(5000)).acceptVerifiedUpdate(eq("42"), update.capture(), eq("tok"));
        assertThat(update.getValue().path("message").path("text").asText()).isEqualTo("hi");
        assertThat(tenantSeen.get()).isEqualTo("tenant_abc");
        // A webhook must not be registered while polling, and the next poll acknowledges update 7.
        assertThat(calls).contains("deleteWebhook");
        long deadline = System.currentTimeMillis() + 5000;
        while (getUpdatesBodies.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(getUpdatesBodies).hasSizeGreaterThanOrEqualTo(2);
        assertThat(MAPPER.readTree(getUpdatesBodies.get(1)).path("offset").asLong()).isEqualTo(8);
    }

    @Test
    void doesNothingWhenAWebhookIsConfigured() {
        TelegramApiClient api = mock(TelegramApiClient.class);
        service =
                new TelegramPollingService(
                        mock(TenantResolutionService.class),
                        mock(TelegramCredentialRepository.class),
                        mock(EncryptionService.class),
                        api,
                        mock(TelegramUpdateService.class),
                        "https://x.example");

        service.ensurePolling("42", "tenant_abc");

        assertThat(service.active()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(api);
    }
}
