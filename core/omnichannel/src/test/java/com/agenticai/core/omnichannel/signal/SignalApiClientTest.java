package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a fake signal-cli: REST calls, the receive websocket, and the outbound policy. */
class SignalApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NUMBER = "+4915112345678";

    private FakeSignalCli cli;
    private SignalApiClient client;

    @BeforeEach
    void start() throws Exception {
        cli = new FakeSignalCli();
        SsrfGuard guard = cli.guard();
        client = new SignalApiClient(MAPPER, () -> guard);
    }

    @AfterEach
    void stop() throws Exception {
        cli.close();
    }

    private SignalConnection connection(String token) {
        return new SignalConnection(cli.baseUrl(), NUMBER, token, UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);
    }

    @Test
    void readsTheServerModeAndRegisteredAccounts() {
        cli.mode = "json-rpc";

        SignalApiClient.About about = client.about(connection(null));

        assertThat(about.mode()).isEqualTo("json-rpc");
        assertThat(about.websocketReceive()).isTrue();
        assertThat(client.accounts(connection(null))).containsExactly(NUMBER);
    }

    @Test
    void longPollsForMessagesWithoutDownloadingAttachments() {
        cli.receiveBatches.add("[{\"envelope\":{\"sourceNumber\":\"+4917600000001\",\"dataMessage\":{\"message\":\"hi\"}}}]");

        JsonNode batch = client.receive(connection(null), 1);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).path("envelope").path("dataMessage").path("message").asText()).isEqualTo("hi");
        String line = cli.requestLines.get(cli.requestLines.size() - 1);
        assertThat(line).contains("/v1/receive/%2B4915112345678").contains("timeout=1").contains("ignore_attachments=true");
    }

    @Test
    void anEmptyPollIsAnEmptyArray() {
        assertThat(client.receive(connection(null), 1)).isEmpty();
    }

    @Test
    void sendsTextToAPersonAndAGroup() throws Exception {
        client.send(connection(null), "+4917600000001", "hello", List.of());
        client.send(connection(null), SignalAddress.toRecipient("group:Z3JvdXBpZGdyb3VwaWQ="), "to all", List.of());

        JsonNode first = MAPPER.readTree(cli.sentBodies.get(0));
        assertThat(first.path("message").asText()).isEqualTo("hello");
        assertThat(first.path("number").asText()).isEqualTo(NUMBER);
        assertThat(first.path("recipients").get(0).asText()).isEqualTo("+4917600000001");
        assertThat(first.has("base64_attachments")).isFalse();
        JsonNode second = MAPPER.readTree(cli.sentBodies.get(1));
        assertThat(second.path("recipients").get(0).asText()).startsWith("group.");
    }

    @Test
    void sendsAnAttachmentAsADataUri() throws Exception {
        byte[] bytes = {1, 2, 3};

        client.send(
                connection(null),
                "+4917600000001",
                "see file",
                List.of(new SignalApiClient.Attachment(bytes, "image/png", "cat;evil,name.png")));

        String uri = MAPPER.readTree(cli.sentBodies.get(0)).path("base64_attachments").get(0).asText();
        assertThat(uri).startsWith("data:image/png;filename=cat_evil_name.png;base64,");
        assertThat(Base64.getDecoder().decode(uri.substring(uri.indexOf("base64,") + 7))).isEqualTo(bytes);
    }

    @Test
    void typingIndicatorFailuresAreSwallowed() {
        cli.requiredToken = "secret"; // 401 for a token-less call
        client.sendTypingIndicator(connection(null), "+4917600000001");
        client.sendTypingIndicator(connection("secret"), "+4917600000001");

        assertThat(cli.typingBodies).hasSize(1);
    }

    @Test
    void sendsTheBearerTokenAndRefusesWithoutIt() {
        cli.requiredToken = "s3cr3t";

        assertThatThrownBy(() -> client.about(connection(null)))
                .isInstanceOfSatisfying(SignalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.retryable()).isFalse();
                });
        assertThat(client.about(connection("s3cr3t")).mode()).isEqualTo("normal");
        assertThat(cli.authHeaders).contains("Bearer s3cr3t");
    }

    @Test
    void aClientErrorIsPermanentAndAServerErrorCanBeRetried() {
        cli.sendStatus = 400;
        assertThatThrownBy(() -> client.send(connection(null), "+4917600000001", "x", List.of()))
                .isInstanceOfSatisfying(SignalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("Unregistered user");
                });

        cli.sendStatus = 503;
        assertThatThrownBy(() -> client.send(connection(null), "+4917600000001", "x", List.of()))
                .isInstanceOfSatisfying(SignalApiException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void anUnreachableServerIsRetryableAndNeverEchoesTheToken() throws Exception {
        String url = cli.baseUrl();
        cli.close();
        SignalConnection c = new SignalConnection(url, NUMBER, "tok-123", UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);

        assertThatThrownBy(() -> client.about(c))
                .isInstanceOfSatisfying(SignalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(SignalApiException.IO);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("tok-123");
                });
    }

    @Test
    void aPrivateHostIsRefusedUnlessTheOperatorListedIt() {
        SignalApiClient strict = new SignalApiClient(MAPPER, SsrfGuard::strict);

        assertThatThrownBy(() -> strict.about(connection(null)))
                .isInstanceOfSatisfying(SignalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(SignalApiException.REFUSED);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("allowed-private-hosts");
                });
        assertThat(cli.requestLines).isEmpty();
    }

    @Test
    void aTokenIsNeverSentOverClearTextToAnUnlistedHost() {
        // 203.0.113.x is a documentation range: blocked, and with a resolver stub even a public-looking host stays http.
        SsrfGuard publicHost = SsrfGuard.of(java.util.Set.of(), host -> new java.net.InetAddress[] {
            java.net.InetAddress.getByAddress(new byte[] {8, 8, 8, 8})
        });
        SignalApiClient publicClient = new SignalApiClient(MAPPER, () -> publicHost);
        SignalConnection c = new SignalConnection(
                "http://signal.example.org", NUMBER, "tok", UnknownSenderPolicy.PAIR, SignalGroupMode.IGNORE);

        assertThatThrownBy(() -> publicClient.about(c))
                .isInstanceOfSatisfying(SignalApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(SignalApiException.REFUSED);
                    assertThat(e.getMessage()).contains("https");
                });
    }

    @Test
    void receivesFramesOverTheJsonRpcWebsocketAndSeesTheServerClose() throws Exception {
        cli.mode = "json-rpc";
        cli.requiredToken = "ws-token";
        try (SignalStream stream = client.openStream(connection("ws-token"))) {
            assertThat(stream.isOpen()).isTrue();
            assertThat(stream.poll(Duration.ofMillis(100))).isNull();

            cli.wsFrames.add("{\"envelope\":{\"sourceNumber\":\"+4917600000001\",\"dataMessage\":{\"message\":\"one\"}}}");
            JsonNode frame = stream.poll(Duration.ofSeconds(5));
            assertThat(frame).isNotNull();
            assertThat(frame.path("envelope").path("dataMessage").path("message").asText()).isEqualTo("one");

            // A large frame crosses the JDK's fragmentation boundary.
            String big = "y".repeat(100_000);
            cli.wsFrames.add("{\"envelope\":{\"sourceNumber\":\"+4917600000001\",\"dataMessage\":{\"message\":\"" + big + "\"}}}");
            JsonNode large = stream.poll(Duration.ofSeconds(5));
            assertThat(large.path("envelope").path("dataMessage").path("message").asText()).hasSize(100_000);

            cli.dropConnections();
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 50; i++) {
                            stream.poll(Duration.ofMillis(200));
                        }
                    })
                    .isInstanceOf(SignalApiException.class);
            assertThat(stream.isOpen()).isFalse();
        }
        assertThat(cli.authHeaders).contains("Bearer ws-token");
    }

    @Test
    void theWebsocketIsAlsoSubjectToTheOutboundPolicy() {
        SignalApiClient strict = new SignalApiClient(MAPPER, SsrfGuard::strict);

        assertThatThrownBy(() -> strict.openStream(connection(null)))
                .isInstanceOfSatisfying(SignalApiException.class, e -> assertThat(e.status()).isEqualTo(SignalApiException.REFUSED));
    }

    @Test
    void connectionToStringHidesTheTokenAndMostOfTheNumber() {
        String text = connection("super-secret").toString();

        assertThat(text).doesNotContain("super-secret").doesNotContain(NUMBER).contains("***5678");
    }
}
