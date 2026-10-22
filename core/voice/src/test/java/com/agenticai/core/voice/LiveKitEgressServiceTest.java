package com.agenticai.core.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.storage.StorageProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies {@link LiveKitEgressService} builds the request LiveKit's Egress (Twirp) API actually
 * documents - see the class javadoc for the sources this shape was checked against. This cannot
 * exercise a real LiveKit Egress worker (none runs in this test environment - see the class's own
 * javadoc on what is/isn't wired up yet), so instead it stands up a plain {@link HttpServer} stub
 * that records the request LiveKitEgressService sent and replies with a minimal, LiveKit-shaped
 * JSON body. That verifies the wire format (URL path, auth header, JSON field names/values) this
 * class produces without asserting anything about LiveKit's own behavior.
 */
class LiveKitEgressServiceTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-at-least-32-bytes-long!!";

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> nextResponseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> nextResponseStatus = new AtomicReference<>(200);

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/twirp/livekit.Egress/",
                exchange -> {
                    lastPath.set(exchange.getRequestURI().getPath());
                    lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    lastBody.set(new String(exchange.getRequestBody().readAllBytes()));
                    byte[] response = nextResponseBody.get().getBytes();
                    exchange.sendResponseHeaders(nextResponseStatus.get(), response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LiveKitEgressService serviceWithEnabled(boolean enabled) {
        LiveKitTokenService tokenService =
                new LiveKitTokenService(API_KEY, API_SECRET, "ws://localhost:7880", 3600);
        StorageProperties storageProperties = new StorageProperties();
        storageProperties.setEndpoint("http://minio:9000");
        storageProperties.setRegion("us-east-1");
        storageProperties.setBucket("agentic-ai");
        storageProperties.setAccessKey("minioadmin");
        storageProperties.setSecretKey("minioadmin");
        storageProperties.setPathStyleAccess(true);
        return new LiveKitEgressService(
                tokenService, storageProperties, enabled, baseUrl(), "OGG", "voice-recordings");
    }

    @Test
    void startRoomRecording_postsTheDocumentedTwirpRequestShape() throws Exception {
        nextResponseBody.set("{\"egress_id\":\"EG_abc123\",\"status\":\"EGRESS_STARTING\"}");
        LiveKitEgressService service = serviceWithEnabled(true);

        LiveKitEgressService.EgressStartResult result =
                service.startRoomRecording("tenant-1", "voice-room-1");

        assertThat(result.egressId()).isEqualTo("EG_abc123");
        assertThat(result.objectKey())
                .startsWith("voice-recordings/tenant-1/voice-room-1-")
                .endsWith(".ogg");

        assertThat(lastPath.get()).isEqualTo("/twirp/livekit.Egress/StartRoomCompositeEgress");
        assertThat(lastAuthHeader.get()).startsWith("Bearer ");

        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("room_name").asString()).isEqualTo("voice-room-1");
        assertThat(body.get("audio_only").asBoolean()).isTrue();
        JsonNode fileOutput = body.get("file_outputs").get(0);
        assertThat(fileOutput.get("file_type").asString()).isEqualTo("OGG");
        assertThat(fileOutput.get("filepath").asString()).isEqualTo(result.objectKey());
        JsonNode s3 = fileOutput.get("s3");
        assertThat(s3.get("access_key").asString()).isEqualTo("minioadmin");
        assertThat(s3.get("secret").asString()).isEqualTo("minioadmin");
        assertThat(s3.get("bucket").asString()).isEqualTo("agentic-ai");
        assertThat(s3.get("endpoint").asString()).isEqualTo("http://minio:9000");
        assertThat(s3.get("force_path_style").asBoolean()).isTrue();

        // The Authorization bearer token itself must carry the roomRecord
        // grant Egress calls require, not a room-join grant.
        String jwt = lastAuthHeader.get().substring("Bearer ".length());
        var claims = com.nimbusds.jwt.SignedJWT.parse(jwt).getJWTClaimsSet();
        @SuppressWarnings("unchecked")
        var videoGrant = (java.util.Map<String, Object>) claims.getClaim("video");
        assertThat(videoGrant.get("roomRecord")).isEqualTo(true);
    }

    @Test
    void startRoomRecording_failsClearlyWithoutAStaticS3KeyPair() {
        LiveKitTokenService tokenService =
                new LiveKitTokenService(API_KEY, API_SECRET, "ws://localhost:7880", 3600);
        for (String[] mode :
                new String[][] {
                    {"s3", "default"}, {"azure-blob", "static"}, {"azure-blob", "default"}
                }) {
            StorageProperties properties = new StorageProperties();
            properties.setType(mode[0]);
            properties.setCredentials(mode[1]);
            LiveKitEgressService service =
                    new LiveKitEgressService(
                            tokenService, properties, true, baseUrl(), "OGG", "voice-recordings");

            assertThatThrownBy(() -> service.startRoomRecording("tenant-1", "voice-room-1"))
                    .isInstanceOf(LiveKitEgressException.class)
                    .hasMessageContaining("app.storage.credentials=static");
        }
        assertThat(lastPath.get()).as("no request reached LiveKit").isNull();
    }

    @Test
    void stopRecording_postsEgressIdToStopEgress() throws Exception {
        nextResponseBody.set("{\"egress_id\":\"EG_abc123\",\"status\":\"EGRESS_COMPLETE\"}");
        LiveKitEgressService service = serviceWithEnabled(true);

        service.stopRecording("EG_abc123");

        assertThat(lastPath.get()).isEqualTo("/twirp/livekit.Egress/StopEgress");
        ObjectMapper mapper = com.agenticai.core.json.JsonMappers.plain();
        JsonNode body = mapper.readTree(lastBody.get());
        assertThat(body.get("egress_id").asString()).isEqualTo("EG_abc123");
    }

    @Test
    void stopRecording_isANoOpForABlankEgressId() {
        LiveKitEgressService service = serviceWithEnabled(true);
        service.stopRecording(null);
        service.stopRecording("");
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void startRoomRecording_throwsLiveKitEgressExceptionOnNon2xxResponse() {
        nextResponseStatus.set(500);
        nextResponseBody.set("{\"code\":\"internal\",\"msg\":\"egress not connected\"}");
        LiveKitEgressService service = serviceWithEnabled(true);

        assertThatThrownBy(() -> service.startRoomRecording("tenant-1", "voice-room-1"))
                .isInstanceOf(LiveKitEgressException.class)
                .hasMessageContaining("500");
    }

    @Test
    void startRoomRecording_throwsLiveKitNotConfiguredWhenApiKeyMissing() {
        LiveKitTokenService unconfigured =
                new LiveKitTokenService("", "", "ws://localhost:7880", 3600);
        StorageProperties storageProperties = new StorageProperties();
        LiveKitEgressService service =
                new LiveKitEgressService(
                        unconfigured,
                        storageProperties,
                        true,
                        baseUrl(),
                        "OGG",
                        "voice-recordings");

        assertThatThrownBy(() -> service.startRoomRecording("tenant-1", "voice-room-1"))
                .isInstanceOf(LiveKitNotConfiguredException.class);
    }

    @Test
    void objectKeySanitizesHostileRoomOrTenantNames() throws Exception {
        nextResponseBody.set("{\"egress_id\":\"EG_x\"}");
        LiveKitEgressService service = serviceWithEnabled(true);

        LiveKitEgressService.EgressStartResult result =
                service.startRoomRecording("../../etc", "room/../../passwd");

        assertThat(result.objectKey())
                .doesNotContain("..")
                .doesNotContain("/etc")
                .doesNotContain("passwd/");
        assertThat(URI.create("http://x/" + result.objectKey())).isNotNull();
    }
}
