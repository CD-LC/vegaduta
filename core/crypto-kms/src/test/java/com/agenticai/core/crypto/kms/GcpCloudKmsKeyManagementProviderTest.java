package com.agenticai.core.crypto.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.crypto.EnvelopeEncryptionService;
import com.agenticai.core.crypto.WrappedDataKey;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs the real REST client against an in-process fake of the Cloud KMS encrypt/decrypt endpoints.
 */
class GcpCloudKmsKeyManagementProviderTest {

    private static final String KEY = "projects/p/locations/europe-west1/keyRings/r/cryptoKeys/k";
    private static final ObjectMapper JSON = com.agenticai.core.json.JsonMappers.plain();

    private HttpServer server;
    private String endpoint;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> aad = new CopyOnWriteArrayList<>();
    private volatile int failWith = 0;

    @BeforeEach
    void start() throws Exception {
        server =
                HttpServer.create(
                        new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/",
                ex -> {
                    paths.add(ex.getRequestURI().getPath());
                    auth.add(ex.getRequestHeaders().getFirst("Authorization"));
                    byte[] reply;
                    int status = 200;
                    if (failWith != 0) {
                        status = failWith;
                        reply =
                                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"no access\"}}"
                                        .getBytes(StandardCharsets.UTF_8);
                    } else {
                        JsonNode in = JSON.readTree(ex.getRequestBody());
                        boolean enc = ex.getRequestURI().getPath().endsWith(":encrypt");
                        String field = enc ? "plaintext" : "ciphertext";
                        aad.add(in.path("additionalAuthenticatedData").asString());
                        byte[] data = Base64.getDecoder().decode(in.path(field).asString());
                        byte[] out = new byte[data.length];
                        for (int i = 0; i < data.length; i++) {
                            out[i] = (byte) (data[i] ^ 0x33);
                        }
                        String json =
                                "{\""
                                        + (enc ? "ciphertext" : "plaintext")
                                        + "\":\""
                                        + Base64.getEncoder().encodeToString(out)
                                        + "\"}";
                        reply = json.getBytes(StandardCharsets.UTF_8);
                    }
                    ex.sendResponseHeaders(status, reply.length);
                    ex.getResponseBody().write(reply);
                    ex.close();
                });
        server.start();
        endpoint =
                "http://"
                        + server.getAddress().getHostString()
                        + ":"
                        + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GcpCloudKmsKeyManagementProvider provider(String keyName) {
        return new GcpCloudKmsKeyManagementProvider(
                keyName, endpoint + "/", true, () -> "tok", HttpClient.newHttpClient());
    }

    @Test
    void roundTripThroughEnvelopeService() {
        GcpCloudKmsKeyManagementProvider provider = provider(KEY);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);

        assertEquals("channel-secret", service.decrypt(service.encrypt("channel-secret")));
        assertEquals("gcp:" + KEY, provider.providerId());
        assertEquals(List.of("/v1/" + KEY + ":encrypt", "/v1/" + KEY + ":decrypt"), paths);
        assertEquals(List.of("Bearer tok", "Bearer tok"), auth);
        String expectedAad =
                Base64.getEncoder()
                        .encodeToString(
                                "purpose=vegaduta-envelope".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(expectedAad, expectedAad), aad);
    }

    @Test
    void wrappedKeyDiffersFromPlaintextAndUnwraps() {
        GcpCloudKmsKeyManagementProvider provider = provider(KEY);
        WrappedDataKey k = provider.generateDataKey();
        assertEquals(32, k.plaintextKey().length);
        assertFalse(java.util.Arrays.equals(k.plaintextKey(), k.wrappedKey()));
        assertArrayEquals(k.plaintextKey(), provider.unwrapDataKey(k.wrappedKey()));
    }

    @Test
    void envelopeWrappedUnderAnotherKeyIsRefusedByKeyName() {
        String envelope = new EnvelopeEncryptionService(provider(KEY)).encrypt("v");
        EnvelopeEncryptionService other =
                new EnvelopeEncryptionService(
                        provider("projects/p/locations/europe-west1/keyRings/r/cryptoKeys/other"));
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> other.decrypt(envelope));
        assertTrue(e.getMessage().contains("gcp:" + KEY), e.getMessage());
    }

    @Test
    void httpErrorsAreReportedWithStatusAndGoogleMessage() {
        failWith = 403;
        GcpCloudKmsKeyManagementProvider provider = provider(KEY);
        IllegalStateException gen =
                assertThrows(IllegalStateException.class, provider::generateDataKey);
        assertTrue(gen.getMessage().contains("HTTP 403 no access"), gen.getMessage());
        IllegalStateException dec =
                assertThrows(
                        IllegalStateException.class, () -> provider.unwrapDataKey(new byte[] {1}));
        assertTrue(dec.getMessage().contains(":decrypt failed"), dec.getMessage());
    }

    @Test
    void unreachableEndpointIsAnIllegalState() {
        server.stop(0);
        assertThrows(IllegalStateException.class, () -> provider(KEY).generateDataKey());
    }

    @Test
    void keyNameMustBeACryptoKeyWithoutVersion() {
        assertThrows(IllegalStateException.class, () -> provider(""));
        assertThrows(IllegalStateException.class, () -> provider("keys/k"));
        assertThrows(IllegalStateException.class, () -> provider(KEY + "/cryptoKeyVersions/1"));
    }

    @Test
    void plainHttpEndpointIsRefusedUnlessTestSettingAllowsLoopback() {
        for (String bad :
                new String[] {
                    "http://cloudkms.googleapis.com", "http://example.com", "ftp://x", "cloudkms"
                }) {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    new GcpCloudKmsKeyManagementProvider(
                                            KEY, bad, true, () -> "t", HttpClient.newHttpClient()));
            assertTrue(e.getMessage().contains("must be an https URL"), e.getMessage());
        }
        // loopback http without the setting is refused too
        assertThrows(
                IllegalStateException.class,
                () ->
                        new GcpCloudKmsKeyManagementProvider(
                                KEY,
                                "http://127.0.0.1:1",
                                false,
                                () -> "t",
                                HttpClient.newHttpClient()));
        // https is fine without the setting
        new GcpCloudKmsKeyManagementProvider(
                KEY,
                "https://cloudkms.googleapis.com/",
                false,
                () -> "t",
                HttpClient.newHttpClient());
    }
}
