package com.agenticai.core.providers;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exchanges a GCP service-account key for a short-lived Vertex AI OAuth2
 * bearer token, cached per credential and refreshed just before expiry. The
 * one piece every Vertex-backed client (chat, image, video) shares - see
 * {@link VertexPoolProperties} for the platform's own 3-project pool, and
 * {@link VertexCredential} for the tenant-BYOK shape this same method also
 * accepts.
 *
 * <p>The cached token lives on the {@link GoogleCredentials} instance and the
 * expiry policy is the google-auth library's, deliberately: only {@code
 * refresh()}/{@code refreshIfExpired()} publish a fetched token to the instance
 * state that {@code getAccessToken()} reads. {@code refreshAccessToken()} is
 * the bare "go fetch one" primitive - it returns a token WITHOUT storing it
 * (the backing field is private to {@code OAuth2Credentials}, so the subclass
 * overrides that perform the HTTP call cannot write it). Calling that instead,
 * as this class did until 2026-07-29, left {@code getAccessToken()} returning
 * null forever, so the cache never hit and every Vertex chat/image/video
 * request paid a full blocking OAuth2 round-trip - while holding the lock
 * below, serializing concurrent callers behind it.
 */
@Component
public class VertexAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(VertexAccessTokenProvider.class);
    private static final List<String> SCOPES = List.of("https://www.googleapis.com/auth/cloud-platform");

    private final VertexPoolProperties pool;
    private final Map<String, GoogleCredentials> credentialsByKey = new ConcurrentHashMap<>();

    /**
     * One lock per cache key, guarding that key's token refresh - see
     * {@link #accessToken} for why this is a {@link ReentrantLock} and not
     * {@code synchronized}. Keyed identically to {@link #credentialsByKey} so
     * two different pool slots (or two tenants' BYOK credentials) never
     * contend with each other; entries are never removed, and the key space is
     * bounded by "configured pool slots + distinct BYOK credentials", the same
     * bound {@link #credentialsByKey} already lives with.
     */
    private final Map<String, ReentrantLock> refreshLocksByKey = new ConcurrentHashMap<>();

    public VertexAccessTokenProvider(VertexPoolProperties pool) {
        this.pool = pool;
    }

    /** Platform pool slots that are both configured and enabled, tried lowest-priority-first, mirroring {@code PlatformHostedLlmProviderService}'s failover order. */
    public List<VertexPoolProperties.Slot> usablePoolSlots() {
        if (!pool.isEnabled()) {
            return List.of();
        }
        return pool.getSlots().stream()
            .filter(VertexPoolProperties.Slot::isUsable)
            .sorted(Comparator.comparingInt(VertexPoolProperties.Slot::getPriority))
            .toList();
    }

    public boolean isPoolConfigured() {
        return !usablePoolSlots().isEmpty();
    }

    /**
     * @throws VertexCredentialException if ADC can't be resolved in this
     *     environment (when {@link VertexPoolProperties.Slot#isUseApplicationDefaultCredentials()}),
     *     the slot's key file cannot be read, or the token cannot be refreshed.
     */
    public String accessTokenForPoolSlot(VertexPoolProperties.Slot slot) {
        if (slot.isUseApplicationDefaultCredentials()) {
            String cacheKey = "pool-adc:" + slot.getProjectId();
            return accessToken(cacheKey, VertexAccessTokenProvider::loadFromApplicationDefault);
        }
        String cacheKey = "pool:" + slot.getProjectId();
        return accessToken(cacheKey, () -> loadFromFile(slot.getServiceAccountKeyPath()));
    }

    /** Same as {@link #accessTokenForPoolSlot}, for a tenant's own BYOK {@link VertexCredential} instead of a platform pool slot. */
    public String accessTokenForCredential(VertexCredential credential) {
        String cacheKey = "byok:" + credential.projectId() + ":" + credential.serviceAccountKeyJson().hashCode();
        return accessToken(cacheKey, () -> loadFromJson(credential.serviceAccountKeyJson()));
    }

    /**
     * Same as {@link #accessTokenForPoolSlot}/{@link #accessTokenForCredential},
     * for an admin-configured {@code PlatformHostedLlmProvider} row (V169's
     * {@code VERTEX_ADC}/{@code VERTEX_SERVICE_ACCOUNT} auth types) rather than
     * a static pool slot or a tenant's BYOK credential - see {@code
     * PlatformHostedLlmAuthType}'s javadoc for what those two types mean.
     *
     * @param serviceAccountKeyJsonOrNull the row's decrypted service-account
     *     JSON when {@code useApplicationDefaultCredentials} is {@code false};
     *     ignored (and may be {@code null}) otherwise.
     */
    public String accessTokenForHostedProvider(
        String projectId, boolean useApplicationDefaultCredentials, String serviceAccountKeyJsonOrNull
    ) {
        if (useApplicationDefaultCredentials) {
            String cacheKey = "hosted-adc:" + projectId;
            return accessToken(cacheKey, VertexAccessTokenProvider::loadFromApplicationDefault);
        }
        String cacheKey = "hosted-sa:" + projectId + ":" + serviceAccountKeyJsonOrNull.hashCode();
        return accessToken(cacheKey, () -> loadFromJson(serviceAccountKeyJsonOrNull));
    }

    /**
     * <b>{@link ReentrantLock}, not {@code synchronized} - deliberate, and a
     * production-only concern.</b> The app runs with
     * {@code spring.threads.virtual.enabled: true}, so this method is reached
     * on a virtual thread by every Vertex-backed chat, image and video call.
     * {@link GoogleCredentials#refreshIfExpired()} makes a blocking network
     * round-trip to Google's token endpoint when the token is actually due,
     * and it happens while the lock is held (it must - that is the point of
     * the lock). Blocking inside
     * {@code synchronized} PINS the carrier thread on JDK 21, which is what
     * production runs ({@code eclipse-temurin:21-jre-jammy}, see
     * {@code core/Dockerfile}); JEP 491 removed that pinning in JDK 24, so on
     * a JDK 24 dev box this looks completely fine. Because the monitor was
     * shared per credential, concurrent Vertex callers both serialized on it
     * AND each pinned a carrier while doing so. {@link ReentrantLock} unmounts
     * the virtual thread while it waits and while the holder blocks, and is
     * correct on 21 and 24 alike.
     *
     * <p>The locking SEMANTICS are unchanged on purpose: same critical
     * section (refresh the token if it is due, then return its value), same
     * mutual exclusion scope (per credential/cache key - the old monitor was
     * the {@code GoogleCredentials} instance, which is itself one-per-cache-key),
     * and the same "drop the cached credential so the next caller reloads it
     * from disk/JSON" behaviour on {@link IOException}.
     *
     * <p>Package-private rather than private purely as a test seam - {@code
     * VertexAccessTokenProviderTest} drives it with a fake {@link
     * GoogleCredentials} so the caching and locking can be asserted without a
     * network round-trip. Call the public {@code accessTokenFor*} methods.
     */
    String accessToken(String cacheKey, java.util.function.Supplier<GoogleCredentials> loader) {
        GoogleCredentials credentials = credentialsByKey.computeIfAbsent(cacheKey, k -> loader.get());
        ReentrantLock lock = refreshLocksByKey.computeIfAbsent(cacheKey, k -> new ReentrantLock());
        lock.lock();
        try {
            // Refreshes only when the library judges the token expired, and -
            // the whole point - PUBLISHES what it fetches to the credential's
            // own state, so the getAccessToken() below is a cache hit on every
            // subsequent call. See this class's javadoc for why the old
            // refreshAccessToken() call could never populate that state.
            credentials.refreshIfExpired();
            AccessToken token = credentials.getAccessToken();
            if (token == null) {
                credentialsByKey.remove(cacheKey);
                throw new VertexCredentialException(
                    "Vertex AI credentials yielded no access token after a refresh");
            }
            return token.getTokenValue();
        } catch (IOException e) {
            credentialsByKey.remove(cacheKey);
            throw new VertexCredentialException("Could not refresh a Vertex AI access token", e);
        } finally {
            lock.unlock();
        }
    }

    private static GoogleCredentials loadFromFile(String keyPath) {
        try (InputStream in = Files.newInputStream(Path.of(keyPath))) {
            return GoogleCredentials.fromStream(in).createScoped(SCOPES);
        } catch (IOException e) {
            log.warn("Could not load Vertex AI service-account key from {}", keyPath);
            throw new VertexCredentialException("Could not load the Vertex AI service-account key file", e);
        }
    }

    private static GoogleCredentials loadFromJson(String json) {
        try (InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return GoogleCredentials.fromStream(in).createScoped(SCOPES);
        } catch (IOException e) {
            throw new VertexCredentialException("The stored service-account key is not valid JSON", e);
        }
    }

    /**
     * No key file, no inline key JSON - resolves whatever Application
     * Default Credentials finds in this environment: a workload-identity/
     * attached service account on GCP, or the local {@code gcloud auth
     * application-default login} file otherwise. See {@link
     * VertexPoolProperties}'s class javadoc for the ADC setup command.
     */
    private static GoogleCredentials loadFromApplicationDefault() {
        try {
            return GoogleCredentials.getApplicationDefault().createScoped(SCOPES);
        } catch (IOException e) {
            log.warn("Could not resolve Application Default Credentials for Vertex AI");
            throw new VertexCredentialException(
                "Could not resolve Application Default Credentials. Locally, run: "
                    + "bash <(curl -sSL https://storage.googleapis.com/cloud-samples-data/adc/setup_adc.sh). "
                    + "In a GCP-hosted environment, attach a service account or enable workload identity instead.",
                e
            );
        }
    }
}
