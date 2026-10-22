package com.agenticai.core.omnichannel.whatsappqr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a fake bridge: session lifecycle, the event poll, send and the secret. */
class WhatsAppQrClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = "3f6f1b0c-7d4a-4b2c-9e8f-1a2b3c4d5e6f";
    private static final String SECRET = "bridge-secret-0123";

    private FakeWhatsAppQrBridge bridge;
    private WhatsAppQrClient client;

    @BeforeEach
    void start() throws Exception {
        bridge = new FakeWhatsAppQrBridge();
        bridge.requiredSecret = SECRET;
        client = new WhatsAppQrClient(MAPPER, bridge::guard, bridge.baseUrl(), SECRET);
    }

    @AfterEach
    void stop() {
        bridge.close();
    }

    @Test
    void unconfiguredMeansTheChannelIsAbsent() {
        assertThat(new WhatsAppQrClient(MAPPER, "", "").configured()).isFalse();
        assertThat(new WhatsAppQrClient(MAPPER, bridge.baseUrl(), "").configured()).isFalse();
        assertThat(client.configured()).isTrue();
    }

    @Test
    void ensureCreatesAndResumesASession() {
        JsonNode first = client.ensureSession(KEY);
        assertThat(first.path("status").asText()).isEqualTo("QR_PENDING");
        assertThat(first.path("qr").asText()).isEqualTo("2@test-qr-payload");
        // Second call resumes, not recreates.
        assertThat(client.ensureSession(KEY).path("status").asText()).isEqualTo("QR_PENDING");
        assertThat(bridge.authHeaders).allMatch(h -> ("Bearer " + SECRET).equals(h));
    }

    @Test
    void statusReportsConnectedWithTheLinkedNumber() {
        client.ensureSession(KEY);
        FakeWhatsAppQrBridge.Session session = bridge.sessions.get(KEY);
        session.status = "CONNECTED";
        session.qr = null;
        session.phoneNumber = "4915112345678";

        JsonNode status = client.sessionStatus(KEY);

        assertThat(status.path("status").asText()).isEqualTo("CONNECTED");
        assertThat(status.path("phoneNumber").asText()).isEqualTo("4915112345678");
    }

    @Test
    void receiveReturnsStatusAndEvents() {
        client.ensureSession(KEY);
        FakeWhatsAppQrBridge.Session session = bridge.sessions.get(KEY);
        session.status = "CONNECTED";
        session.events =
                "[{\"id\":\"ABC\",\"chatJid\":\"4915@s.whatsapp.net\",\"text\":\"hi\",\"timestampMs\":1}]";

        JsonNode result = client.receive(KEY, 1);

        assertThat(result.path("status").asText()).isEqualTo("CONNECTED");
        assertThat(result.path("events").get(0).path("text").asText()).isEqualTo("hi");
        assertThat(bridge.requestLines.getLast()).contains("/sessions/" + KEY + "/events");
    }

    @Test
    void aForgottenSessionAnswers404() {
        assertThatThrownBy(() -> client.sessionStatus(KEY))
                .isInstanceOf(WhatsAppQrApiException.class)
                .hasMessageContaining("404");
        // ... and ensureSession brings it back after a bridge restart.
        assertThat(client.ensureSession(KEY).path("status").asText()).isEqualTo("QR_PENDING");
    }

    @Test
    void sendPostsTextToTheSession() {
        client.ensureSession(KEY);
        bridge.sessions.get(KEY).status = "CONNECTED";

        client.send(KEY, "4915100000000", "hello");

        assertThat(bridge.sentBodies.getLast())
                .contains("\"to\":\"4915100000000\"")
                .contains("\"text\":\"hello\"");
    }

    @Test
    void sendToANonConnectedSessionIsNotRetryable() {
        client.ensureSession(KEY);

        assertThatThrownBy(() -> client.send(KEY, "4915100000000", "hello"))
                .isInstanceOfSatisfying(
                        WhatsAppQrApiException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void deleteRemovesTheSession() {
        client.ensureSession(KEY);

        client.deleteSession(KEY);

        assertThat(bridge.sessions).isEmpty();
    }
}
