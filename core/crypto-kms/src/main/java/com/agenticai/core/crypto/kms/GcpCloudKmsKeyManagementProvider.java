package com.agenticai.core.crypto.kms;

import com.agenticai.core.crypto.KeyManagementProvider;
import com.agenticai.core.crypto.WrappedDataKey;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Cloud KMS {@link KeyManagementProvider} ({@code app.crypto.kms.provider=gcp}). A fresh
 * AES-256 data key is generated locally and wrapped with the Cloud KMS {@code :encrypt} REST call;
 * {@code :decrypt} reverses it. The REST API over the JDK HTTP client keeps the dependency to the
 * Application Default Credentials library that Vertex AI support already ships (GKE Workload
 * Identity, attached service account, {@code gcloud} login): no key file is stored, and the gRPC
 * client stack is not pulled in.
 *
 * <p>The provider id is {@code gcp:<crypto key resource name>}, which must name a symmetric
 * encryption key ({@code projects/P/locations/L/keyRings/R/cryptoKeys/K}); Cloud KMS picks the
 * right key version on decrypt. The identity needs {@code cloudkms.cryptoKeyVersions.useToEncrypt}
 * and {@code useToDecrypt} (role "Cloud KMS CryptoKey Encrypter/Decrypter").
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.crypto.kms.provider", havingValue = "gcp")
public class GcpCloudKmsKeyManagementProvider implements KeyManagementProvider {

    static final String ID_PREFIX = "gcp:";
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static final Pattern KEY_NAME =
            Pattern.compile("projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+");
    private static final int DATA_KEY_LENGTH_BYTES = 32;

    /** Binds the ciphertext to its purpose, like the AWS encryption context. */
    private static final String AAD =
            Base64.getEncoder()
                    .encodeToString("purpose=vegaduta-envelope".getBytes(StandardCharsets.UTF_8));

    private static final ObjectMapper JSON = JsonMappers.plain();

    private final String keyName;
    private final String endpoint;
    private final Supplier<String> accessToken;
    private final HttpClient http;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public GcpCloudKmsKeyManagementProvider(
            @Value("${app.crypto.kms.gcp.key-name:}") String keyName,
            @Value("${app.crypto.kms.gcp.endpoint:https://cloudkms.googleapis.com}")
                    String endpoint,
            @Value("${app.crypto.kms.gcp.allow-insecure-test-endpoint:false}")
                    boolean allowInsecureTestEndpoint) {
        this(
                keyName,
                endpoint,
                allowInsecureTestEndpoint,
                applicationDefaultToken(),
                defaultHttpClient());
    }

    GcpCloudKmsKeyManagementProvider(
            String keyName,
            String endpoint,
            boolean allowInsecureTestEndpoint,
            Supplier<String> accessToken,
            HttpClient http) {
        this.keyName = KmsIds.requireKey("app.crypto.kms.gcp.key-name", keyName);
        if (!KEY_NAME.matcher(this.keyName).matches()) {
            throw new IllegalStateException(
                    "app.crypto.kms.gcp.key-name must look like"
                            + " projects/P/locations/L/keyRings/R/cryptoKeys/K (no key version)");
        }
        KmsIds.checkProviderIdLength(ID_PREFIX + this.keyName);
        String trimmed = endpoint.trim();
        this.endpoint =
                trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        requireSecureEndpoint(this.endpoint, allowInsecureTestEndpoint);
        this.accessToken = accessToken;
        this.http = http;
    }

    /**
     * The bearer token and the plaintext data key travel in this request, so the endpoint must be
     * https. Plain http is accepted only for a loopback host and only when the test setting says
     * so.
     */
    private static void requireSecureEndpoint(String endpoint, boolean allowInsecureTestEndpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.crypto.kms.gcp.endpoint is not a valid URL: " + e.getMessage(), e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme) && uri.getHost() != null) {
            return;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback =
                host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if ("http".equals(scheme) && loopback && allowInsecureTestEndpoint) {
            return;
        }
        throw new IllegalStateException(
                "app.crypto.kms.gcp.endpoint must be an https URL (the access token and the data"
                        + " key are sent to it). Plain http to localhost/127.0.0.1 is accepted only"
                        + " with app.crypto.kms.gcp.allow-insecure-test-endpoint=true, for tests.");
    }

    private static HttpClient defaultHttpClient() {
        return GuardedJdkHttpClient.defaults(Duration.ofSeconds(10), Duration.ofSeconds(10), false);
    }

    private static Supplier<String> applicationDefaultToken() {
        try {
            GoogleCredentials credentials =
                    GoogleCredentials.getApplicationDefault().createScoped(List.of(SCOPE));
            return () -> {
                try {
                    credentials.refreshIfExpired();
                    return credentials.getAccessToken().getTokenValue();
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Could not get a Google access token for Cloud KMS: " + e.getMessage(),
                            e);
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException(
                    "app.crypto.kms.provider=gcp needs Google Application Default Credentials (GKE"
                            + " Workload Identity, GOOGLE_APPLICATION_CREDENTIALS, gcloud login): "
                            + e.getMessage(),
                    e);
        }
    }

    @Override
    public WrappedDataKey generateDataKey() {
        byte[] plaintext = new byte[DATA_KEY_LENGTH_BYTES];
        secureRandom.nextBytes(plaintext);
        try {
            JsonNode r =
                    call(
                            ":encrypt",
                            Map.of(
                                    "plaintext",
                                    Base64.getEncoder().encodeToString(plaintext),
                                    "additionalAuthenticatedData",
                                    AAD));
            String ciphertext = r.path("ciphertext").asString("");
            if (ciphertext.isEmpty()) {
                throw new IllegalStateException("Cloud KMS encrypt returned no ciphertext");
            }
            return new WrappedDataKey(
                    plaintext, Base64.getDecoder().decode(ciphertext), providerId());
        } catch (RuntimeException e) {
            Arrays.fill(plaintext, (byte) 0);
            throw e;
        }
    }

    @Override
    public byte[] unwrapDataKey(byte[] wrappedKey) {
        JsonNode r =
                call(
                        ":decrypt",
                        Map.of(
                                "ciphertext",
                                Base64.getEncoder().encodeToString(wrappedKey),
                                "additionalAuthenticatedData",
                                AAD));
        String plaintext = r.path("plaintext").asString("");
        if (plaintext.isEmpty()) {
            throw new IllegalStateException("Cloud KMS decrypt returned no plaintext");
        }
        return Base64.getDecoder().decode(plaintext);
    }

    private JsonNode call(String verb, Map<String, String> body) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(endpoint + "/v1/" + keyName + verb))
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer " + accessToken.get())
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(body)))
                            .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = parse(response.body());
            if (response.statusCode() / 100 != 2) {
                // Google error bodies carry {"error":{"status","message"}}; never the request body.
                throw new IllegalStateException(
                        "Cloud KMS"
                                + verb
                                + " failed for '"
                                + keyName
                                + "': HTTP "
                                + response.statusCode()
                                + " "
                                + json.path("error").path("message").asString(""));
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cloud KMS" + verb + " failed for '" + keyName + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Cloud KMS" + verb + " interrupted", e);
        }
    }

    /**
     * A non-JSON body (a proxy's HTML error page) becomes an empty object so the HTTP status is
     * still reported.
     */
    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    @Override
    public String providerId() {
        return ID_PREFIX + keyName;
    }
}
