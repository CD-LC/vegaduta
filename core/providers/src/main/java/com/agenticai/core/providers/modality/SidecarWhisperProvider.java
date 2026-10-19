package com.agenticai.core.providers.modality;

import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.knowledge.SidecarCommunicationException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Speech-to-text provider for the sidecar's local Whisper instance.
 * Requires the "knowledge" profile to be active and the sidecar service to be
 * running. Provides lowest-latency transcription (local network call, no external
 * API).
 *
 * <p>This is the preferred provider for push-to-talk voice turns when available,
 * as it avoids external API dependencies and rate limits.
 *
 * <p>Implementation note: {@code Optional<SidecarClient>} is injected by Spring.
 * The {@link #isAvailable()} check returns false if the optional is empty (the
 * "knowledge" profile is inactive) or if a health check fails.
 *
 * <p><b>Real liveness check (2026-08-08 audit fix #5).</b> {@link
 * #isAvailable()} used to return {@code true} the moment the {@code
 * SidecarClient} bean existed and never re-check - if the sidecar process
 * crashed or hung after startup, this stayed {@code true} forever, so every
 * voice turn during the outage paid a guaranteed-fail {@link #transcribe}
 * call (and its retry/timeout latency) before finally falling through to
 * Sarvam. It now pings the sidecar's unauthenticated {@code /health}
 * endpoint (see {@code SidecarClient}'s own javadoc on why that one endpoint
 * needs no {@code X-Internal-Token}) with a short 2s timeout, caching the
 * result for {@link #HEALTH_CACHE_TTL} so this doesn't add a network
 * round-trip to every single voice turn - only one per cache window. Reads
 * the same {@code app.sidecar.base-url} property {@code SidecarClient}
 * itself resolves, but with a blank default (unlike that class's required
 * property) so this always-registered {@code @Component} doesn't fail
 * application startup on a deployment where the "knowledge" profile - and
 * therefore {@code app.sidecar.base-url} - was never configured; in that
 * case {@link #isAvailable()} falls back to the old bean-presence check
 * (which will already be {@code false}, since {@code sidecarClient} would be
 * empty too). Deliberately uses {@link JdkClientHttpRequestFactory}, not
 * {@code SimpleClientHttpRequestFactory} - see {@code SidecarClient}'s class
 * javadoc ("Transport: JDK HttpClient, not HttpURLConnection") for the
 * virtual-thread carrier-pinning bug that combination causes under this
 * app's {@code spring.threads.virtual.enabled: true} config; reintroducing
 * it here for a "small" health check would be the exact footgun that
 * javadoc exists to prevent.
 */
@Component
public class SidecarWhisperProvider implements SpeechToTextProvider {

    private static final Logger log = LoggerFactory.getLogger(SidecarWhisperProvider.class);

    /** How long a health-check result is trusted before {@link #isAvailable()} re-probes - bounds how quickly a crashed sidecar is noticed against how much per-turn latency a probe adds. */
    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(15);
    /** Short connect+read timeout for the probe itself - this sits in front of every voice turn's provider-selection step, so a wedged sidecar must fail fast rather than adding its own hang on top of the one it's trying to detect. */
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(2);

    private final Optional<SidecarClient> sidecarClient;

    /** {@code null} when {@code app.sidecar.base-url} isn't configured - see the class javadoc. */
    private final @Nullable RestClient healthRestClient;

    private volatile @Nullable Instant lastHealthCheckAt;
    private volatile boolean lastHealthy;

    public SidecarWhisperProvider(
        Optional<SidecarClient> sidecarClient,
        @Value("${app.sidecar.base-url:}") String sidecarBaseUrl
    ) {
        this.sidecarClient = sidecarClient;
        this.healthRestClient = buildHealthRestClient(sidecarBaseUrl);
    }

    private static @Nullable RestClient buildHealthRestClient(@Nullable String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(HEALTH_CHECK_TIMEOUT)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(HEALTH_CHECK_TIMEOUT);
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }

    @Override
    public String transcribe(byte[] audioBytes, String filename) throws SpeechToTextException {
        if (!isAvailable()) {
            throw new SpeechToTextProviderException(
                getUnavailabilityReason(),
                0,
                false  // not retryable - the sidecar is down or not configured
            );
        }

        try {
            String text = sidecarClient.get().transcribeAudio(filename, audioBytes);
            if (text == null || text.trim().isEmpty()) {
                throw new SpeechToTextTranscriptionException(
                    "No speech detected in the audio. Please try speaking more clearly."
                );
            }
            log.debug("Sidecar Whisper transcription successful: {} bytes → {} chars",
                audioBytes.length, text.length());
            return text;
        } catch (SidecarCommunicationException e) {
            log.warn("Sidecar Whisper transcription failed: {}", e.getMessage(), e);
            throw new SpeechToTextProviderException(
                "Sidecar Whisper is temporarily unavailable: " + e.getMessage(),
                e,
                true  // retryable - the sidecar may recover
            );
        } catch (SpeechToTextException e) {
            throw e;  // re-throw our own exceptions as-is
        } catch (Exception e) {
            log.error("Unexpected error during Sidecar Whisper transcription: {}", e.getMessage(), e);
            throw new SpeechToTextProviderException(
                "Sidecar Whisper encountered an unexpected error: " + e.getMessage(),
                e,
                true
            );
        }
    }

    @Override
    public boolean isAvailable() {
        if (sidecarClient.isEmpty()) {
            return false;  // knowledge profile not active
        }
        RestClient client = healthRestClient;
        if (client == null) {
            // app.sidecar.base-url wasn't resolved to a real value even
            // though the sidecarClient bean exists - shouldn't normally
            // happen, since that bean requires the very same property with
            // no default (see the class javadoc). Fall back to the old
            // bean-presence-only signal rather than fail every voice turn.
            return true;
        }

        Instant checkedAt = lastHealthCheckAt;
        if (checkedAt != null && Duration.between(checkedAt, Instant.now()).compareTo(HEALTH_CACHE_TTL) < 0) {
            return lastHealthy;  // still within the cache window - no network call
        }

        boolean healthy = probeHealth(client);
        lastHealthy = healthy;
        lastHealthCheckAt = Instant.now();
        return healthy;
    }

    /** The actual liveness probe backing {@link #isAvailable()} - see the class javadoc for why this is cached rather than called on every check. */
    private boolean probeHealth(RestClient client) {
        try {
            client.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.debug("Sidecar health check failed - treating as unavailable: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String getUnavailabilityReason() {
        if (sidecarClient.isEmpty()) {
            return "Sidecar Whisper is not available. Please enable the knowledge profile or ensure the sidecar service is running.";
        }
        return "Sidecar Whisper is temporarily unavailable. Please try again in a moment.";
    }

    @Override
    public VoiceModelProvider getProvider() {
        return VoiceModelProvider.SIDECAR_WHISPER;
    }
}
