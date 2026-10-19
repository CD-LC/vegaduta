package com.agenticai.core.omnichannel.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.net.SsrfGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Matrix client against a fake homeserver: paths, auth, bodies, errors, and the SSRF guard. */
class MatrixApiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "syt_super_secret_token";

    private FakeHomeserver server;
    private MatrixApiClient client;
    private MatrixApiClient.Endpoint endpoint;

    @BeforeEach
    void start() throws Exception {
        server = new FakeHomeserver();
        client = server.client();
        endpoint = server.endpoint(TOKEN);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void whoAmIReturnsTheAccountAndSendsTheTokenOnlyInTheHeader() {
        server.on("GET /_matrix/client/v3/account/whoami", 200, "{\"user_id\":\"@bot:hs\",\"device_id\":\"D1\"}");

        MatrixApiClient.WhoAmI who = client.whoAmI(endpoint);

        assertThat(who.userId()).isEqualTo("@bot:hs");
        assertThat(who.deviceId()).isEqualTo("D1");
        FakeHomeserver.Request request = server.requests.get(0);
        assertThat(request.headers().get("authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.rawPath() + request.query()).doesNotContain(TOKEN);
    }

    @Test
    void syncSendsTheFilterTheTimeoutAndTheSinceToken() {
        server.on("GET /_matrix/client/v3/sync", 200, "{\"next_batch\":\"s9\"}");

        JsonNode response = client.sync(endpoint, "s8", 1234);

        assertThat(response.path("next_batch").asText()).isEqualTo("s9");
        String query = server.requests.get(0).query();
        assertThat(query).contains("timeout=1234").contains("since=s8").contains("filter=");
        // First sync carries no since token at all.
        client.sync(endpoint, null, 0);
        assertThat(server.requests.get(1).query()).doesNotContain("since=");
    }

    @Test
    void sendTextIsAnIdempotentPutWithTheTransactionIdAndReplyRelation() throws Exception {
        server.on("PUT /_matrix/client/v3/rooms/!room:hs/send/m.room.message/", 200, "{\"event_id\":\"$sent\"}");

        String eventId = client.sendText(endpoint, "!room:hs", "txn-1", "hello", "$orig", null);

        assertThat(eventId).isEqualTo("$sent");
        FakeHomeserver.Request request = server.requests.get(0);
        assertThat(request.rawPath()).isEqualTo("/_matrix/client/v3/rooms/%21room%3Ahs/send/m.room.message/txn-1");
        JsonNode body = MAPPER.readTree(request.body());
        assertThat(body.path("msgtype").asText()).isEqualTo("m.text");
        assertThat(body.path("body").asText()).isEqualTo("hello");
        assertThat(body.path("m.relates_to").path("m.in_reply_to").path("event_id").asText()).isEqualTo("$orig");
    }

    @Test
    void aThreadReplyCarriesTheThreadRelation() throws Exception {
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{\"event_id\":\"$sent\"}");

        client.sendText(endpoint, "!r:hs", "t", "in thread", "$msg", "$root");

        JsonNode relation = MAPPER.readTree(server.requests.get(0).body()).path("m.relates_to");
        assertThat(relation.path("rel_type").asText()).isEqualTo("m.thread");
        assertThat(relation.path("event_id").asText()).isEqualTo("$root");
        assertThat(relation.path("is_falling_back").asBoolean()).isFalse();
        assertThat(relation.path("m.in_reply_to").path("event_id").asText()).isEqualTo("$msg");
    }

    @Test
    void longTextIsSplitIntoSeveralEventsWithDistinctTransactionIds() {
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{\"event_id\":\"$e\"}");

        client.sendText(endpoint, "!r:hs", "txn", "x".repeat(MatrixApiClient.MAX_BODY_CHARS * 2 + 10), null, null);

        assertThat(server.requests).hasSize(3);
        assertThat(server.requests.get(0).rawPath()).endsWith("/txn");
        assertThat(server.requests.get(1).rawPath()).endsWith("/txn-1");
        assertThat(server.requests.get(2).rawPath()).endsWith("/txn-2");
    }

    @Test
    void aRejectedTokenIsReportedWithoutLeakingIt() {
        server.on(
                "GET /_matrix/client/v3/account/whoami",
                401,
                "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"Unrecognised access token\"}");

        assertThatThrownBy(() -> client.whoAmI(endpoint))
                .isInstanceOfSatisfying(
                        MatrixApiException.class,
                        e -> {
                            assertThat(e.isUnknownToken()).isTrue();
                            assertThat(e.status()).isEqualTo(401);
                            assertThat(e.getMessage()).doesNotContain(TOKEN);
                        });
    }

    @Test
    void aRateLimitCarriesTheRequestedWait() {
        server.on(
                "GET /_matrix/client/v3/sync",
                429,
                "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"error\":\"slow down\",\"retry_after_ms\":1500}");

        assertThatThrownBy(() -> client.sync(endpoint, "s", 0))
                .isInstanceOfSatisfying(
                        MatrixApiException.class,
                        e -> {
                            assertThat(e.isRateLimited()).isTrue();
                            assertThat(e.retryAfterMs()).isEqualTo(1500);
                            assertThat(e.isPermanentClientError()).isFalse();
                        });
    }

    @Test
    void sendFileUploadsThenPostsAFileEventOfTheRightKind() throws Exception {
        server.on("POST /_matrix/media/v3/upload", 200, "{\"content_uri\":\"mxc://hs/abc123\"}");
        server.on("PUT /_matrix/client/v3/rooms/", 200, "{\"event_id\":\"$f\"}");

        client.sendFile(endpoint, "!r:hs", "t", "pic.png", "image/png", new byte[] {1, 2, 3}, "a picture", null, null);

        FakeHomeserver.Request upload = server.requests.get(0);
        assertThat(upload.query()).contains("filename=pic.png");
        assertThat(upload.body()).containsExactly(1, 2, 3);
        assertThat(upload.headers().get("content-type")).startsWith("image/png");
        JsonNode event = MAPPER.readTree(server.requests.get(1).body());
        assertThat(event.path("msgtype").asText()).isEqualTo("m.image");
        assertThat(event.path("url").asText()).isEqualTo("mxc://hs/abc123");
        assertThat(event.path("body").asText()).isEqualTo("a picture");
        assertThat(event.path("info").path("size").asInt()).isEqualTo(3);
    }

    @Test
    void downloadUsesAuthenticatedMediaAndFallsBackToTheLegacyEndpoint() {
        server.on("GET /_matrix/client/v1/media/download/", 404, "{\"errcode\":\"M_UNRECOGNIZED\"}");
        server.on(
                "GET /_matrix/media/v3/download/hs/abc123",
                r -> new FakeHomeserver.Response(200, "text/plain", "contents".getBytes(StandardCharsets.UTF_8)));

        byte[] bytes = client.downloadMedia(endpoint, "mxc://hs/abc123", 1024);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("contents");
        assertThat(server.requests.get(0).headers().get("authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void downloadRefusesAnOversizeFileAndAMalformedUri() {
        server.on(
                "GET /_matrix/client/v1/media/download/",
                r -> new FakeHomeserver.Response(200, "text/plain", new byte[2048]));

        assertThatThrownBy(() -> client.downloadMedia(endpoint, "mxc://hs/abc123", 100))
                .isInstanceOfSatisfying(MatrixApiException.class, e -> assertThat(e.errcode()).isEqualTo("M_TOO_LARGE"));
        assertThatThrownBy(() -> client.downloadMedia(endpoint, "https://evil/x", 100))
                .isInstanceOf(MatrixApiException.class);
    }

    @Test
    void aPrivateHomeserverNotOnTheOperatorListIsRefused() {
        MatrixApiClient strict =
                new MatrixApiClient(MAPPER, SsrfGuard::strict);

        assertThatThrownBy(() -> strict.whoAmI(endpoint))
                .isInstanceOfSatisfying(
                        MatrixApiException.class, e -> assertThat(e.errcode()).isEqualTo("M_SSRF_BLOCKED"));
        assertThat(server.requests).isEmpty();
    }

    @Test
    void plainHttpToAPublicHomeserverIsRefusedEvenWhenTheAddressIsFine() {
        SsrfGuard publicGuard =
                SsrfGuard.of(
                        Set.of(),
                        host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {93, (byte) 184, 34, 1})});
        MatrixApiClient c = new MatrixApiClient(MAPPER, () -> publicGuard);

        assertThatThrownBy(() -> c.whoAmI(new MatrixApiClient.Endpoint("http://matrix.example.com", TOKEN)))
                .isInstanceOfSatisfying(
                        MatrixApiException.class, e -> assertThat(e.errcode()).isEqualTo("M_INSECURE_HOMESERVER"));
    }

    @Test
    void theEndpointNeverPrintsTheToken() {
        assertThat(endpoint.toString()).doesNotContain(TOKEN);
    }

    @Test
    void msgtypeFollowsTheMimeType() {
        assertThat(MatrixApiClient.msgtypeFor("image/jpeg")).isEqualTo("m.image");
        assertThat(MatrixApiClient.msgtypeFor("audio/ogg")).isEqualTo("m.audio");
        assertThat(MatrixApiClient.msgtypeFor("video/mp4")).isEqualTo("m.video");
        assertThat(MatrixApiClient.msgtypeFor("application/pdf")).isEqualTo("m.file");
        assertThat(MatrixApiClient.msgtypeFor(null)).isEqualTo("m.file");
    }
}
