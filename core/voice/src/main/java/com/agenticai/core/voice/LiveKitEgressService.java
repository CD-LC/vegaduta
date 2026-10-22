package com.agenticai.core.voice;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import com.agenticai.core.storage.StorageProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts/stops LiveKit Egress recordings of a self-hosted voice call and uploads the result
 * straight to the app's existing S3-compatible bucket (reusing {@link StorageProperties} - see this
 * class's pom.xml/module javadoc for why {@code voice} now depends on {@code storage}).
 *
 * <h2>Why RoomCompositeEgress(audio_only=true), not TrackEgress/TrackCompositeEgress</h2>
 *
 * LiveKit's Egress API offers three shapes (see <a
 * href="https://docs.livekit.io/reference/other/egress/api/">the official reference</a>): {@code
 * RoomCompositeEgress} (records the whole room via a headless-browser compositor), {@code
 * TrackCompositeEgress} (records one participant's specific audio+video track IDs into one encoded
 * file), and {@code TrackEgress} (exports one raw track as-is). This call model only ever has ONE
 * published track in the room - the browser's own microphone (see {@code VoiceCallController}'s
 * javadoc: the agent's synthesized reply is played back directly in the browser via a plain {@code
 * <audio>} tag in {@code CallPanel.tsx}, never published back into the LiveKit room itself), so on
 * paper {@code TrackCompositeEgress} looks like the tighter fit.
 *
 * <p>It was NOT chosen because it needs the specific {@code audio_track_id} LiveKit assigns to that
 * mic track, and this service only ever knows the room name (from {@link
 * VoiceCallController#issueToken}) - the track ID is assigned client-side, after {@code
 * room.localParticipant.setMicrophoneEnabled(true)} resolves, and reporting it back to core would
 * need a new frontend round-trip that is out of scope for this pass (see the module's build notes /
 * PR description for the explicit follow-up this leaves for {@code CallPanel.tsx}). {@code
 * RoomCompositeEgress} with {@code audio_only=true} needs only the room name, matching what's
 * actually available server-side today, at the cost of spinning up a heavier headless-Chrome
 * compositor process per call than a raw track capture would need - an accepted trade-off, not an
 * oversight.
 *
 * <h2>What is NOT yet true</h2>
 *
 * LiveKit's Egress is a genuinely separate worker process (a {@code livekit/egress} container
 * talking to {@code livekit-server} over Redis), not a feature baked into the single {@code
 * livekit-server} image already running in {@code deploy/docker-compose.yml}. That worker container
 * is NOT part of this compose file yet - see this feature's build notes for exactly what still
 * needs adding (an egress worker service, plus a {@code redis:} block in {@code
 * deploy/livekit/livekit.yaml} wiring it to the existing {@code redis} service) before a {@code
 * StartRoomCompositeEgress} call made by this class will do anything other than fail at the
 * LiveKit-server end with "egress not connected". This class is a correct, wire-format-verified
 * client for the API LiveKit documents; it has not been exercised against a live LiveKit+Egress
 * deployment in this pass.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Component
public class LiveKitEgressService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitEgressService.class);

    private final LiveKitTokenService tokenService;
    private final StorageProperties storageProperties;
    private final boolean enabled;
    private final String egressBaseUrl;
    private final String fileType;
    private final String objectKeyPrefix;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public LiveKitEgressService(
            LiveKitTokenService tokenService,
            StorageProperties storageProperties,
            // OFF by default - deliberately not just an infra toggle. Recording a
            // voice call captures the other party's speech as well as the
            // tenant's own agent's, which is a consent/privacy decision (was this
            // disclosed to callers? is there a jurisdiction-specific two-party
            // consent requirement for recorded calls?) that this codebase cannot
            // make on an operator's behalf - see the build notes for this
            // feature, which flags this explicitly as needing a legal/consent-
            // flow decision, not just an engineering one, before any real
            // deployment flips this on.
            @Value("${livekit.egress.enabled:false}") boolean enabled,
            // Internal (compose-network) LiveKit address for calling its Twirp
            // Egress API - deliberately separate from livekit.url (the
            // BROWSER-facing ws:// address returned alongside join tokens).
            // Defaults to the plain compose service DNS name, same "reachable if
            // enabled, inert otherwise" pattern as SIDECAR_BASE_URL/
            // CODE_INTEL_BASE_URL elsewhere in this codebase - RULE 0 doesn't
            // apply here since this URL never reaches a browser.
            @Value("${livekit.egress-url:http://livekit:7880}") String egressBaseUrl,
            // OGG is LiveKit's own recommended container for an audio_only
            // RoomComposite output (MP4-audio-only has had player-compatibility
            // quirks in some of LiveKit's own examples) - overridable per
            // deployment regardless.
            @Value("${livekit.egress.file-type:OGG}") String fileType,
            @Value("${livekit.egress.object-key-prefix:voice-recordings}") String objectKeyPrefix) {
        this.tokenService = tokenService;
        this.storageProperties = storageProperties;
        this.enabled = enabled;
        this.egressBaseUrl = egressBaseUrl;
        this.fileType = fileType;
        this.objectKeyPrefix = objectKeyPrefix;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Whether this deployment has opted into call recording at all - checked by {@link
     * VoiceCallController} before exposing the recording endpoints.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Starts a {@code RoomCompositeEgress} (audio-only) recording of {@code roomName}, uploading
     * straight to this app's configured S3/MinIO bucket under a tenant-scoped key. Computes and
     * returns the eventual object key up front (LiveKit fills in the literal filepath given, no
     * server-side template substitution is relied on here) so a {@link VoiceCallRecording} row can
     * be persisted with a known key even before the recording finishes.
     *
     * @throws LiveKitNotConfiguredException if LIVEKIT_API_KEY/SECRET are unset
     * @throws LiveKitEgressException if the Egress API call itself fails
     */
    public EgressStartResult startRoomRecording(String tenantId, String roomName) {
        String objectKey =
                objectKeyPrefix
                        + "/"
                        + sanitizeKeySegment(tenantId)
                        + "/"
                        + sanitizeKeySegment(roomName)
                        + "-"
                        + Instant.now().toEpochMilli()
                        + "."
                        + fileType.toLowerCase(Locale.ROOT);

        requireStaticS3Credentials(storageProperties);
        Map<String, Object> s3 = new LinkedHashMap<>();
        s3.put("access_key", storageProperties.getAccessKey());
        s3.put("secret", storageProperties.getSecretKey());
        s3.put("bucket", storageProperties.getBucket());
        if (StringUtils.hasText(storageProperties.getRegion())) {
            s3.put("region", storageProperties.getRegion());
        }
        if (StringUtils.hasText(storageProperties.getEndpoint())) {
            s3.put("endpoint", storageProperties.getEndpoint());
        }
        s3.put("force_path_style", storageProperties.isPathStyleAccess());

        Map<String, Object> fileOutput = new LinkedHashMap<>();
        fileOutput.put("file_type", fileType.toUpperCase(Locale.ROOT));
        fileOutput.put("filepath", objectKey);
        fileOutput.put("s3", s3);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("room_name", roomName);
        body.put("audio_only", true);
        body.put("file_outputs", List.of(fileOutput));

        String egressId = callEgress("StartRoomCompositeEgress", body);
        log.info(
                "Started LiveKit RoomCompositeEgress room={} egressId={} objectKey={}",
                LogSafe.clean(roomName),
                egressId,
                objectKey);
        return new EgressStartResult(egressId, objectKey);
    }

    /**
     * The egress worker uploads with an S3 access key and secret it is handed in the request, so
     * recording needs {@code app.storage.type=s3} with {@code app.storage.credentials=static}. With
     * {@code credentials=default} (no keys, the SDK chain) or {@code azure-blob} the properties
     * hold only the MinIO defaults, and LiveKit would accept the request and then fail at upload;
     * fail here, with the reason, instead.
     */
    static void requireStaticS3Credentials(StorageProperties properties) {
        if (!"s3".equalsIgnoreCase(properties.getType())
                || !"static".equalsIgnoreCase(properties.getCredentials())) {
            throw new LiveKitEgressException(
                    "Call recording needs app.storage.type=s3 with app.storage.credentials=static"
                            + " (LiveKit Egress uploads with an S3 access key and secret it is given)."
                            + " It is not supported with type="
                            + properties.getType()
                            + ", credentials="
                            + properties.getCredentials()
                            + "; turn livekit.egress.enabled off or use a"
                            + " static-key S3-compatible bucket.");
        }
    }

    /**
     * Calls {@code StopEgress}. Best-effort by design: a call that already ended on the LiveKit
     * side (room closed, participant disconnected - LiveKit auto-stops egress when there's nothing
     * left to record) makes this a redundant-but-harmless call, not an error the caller needs to
     * handle specially.
     */
    public void stopRecording(String egressId) {
        if (!StringUtils.hasText(egressId)) {
            return;
        }
        callEgress("StopEgress", Map.of("egress_id", egressId));
        log.info("Stopped LiveKit egress {}", egressId);
    }

    private String callEgress(String method, Map<String, Object> body) {
        String authToken = tokenService.issueEgressAuthToken();
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(body);
        } catch (JacksonException e) {
            throw new LiveKitEgressException("Could not serialize LiveKit Egress request body", e);
        }

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(egressBaseUrl + "/twirp/livekit.Egress/" + method))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + authToken)
                        .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                        .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LiveKitEgressException(
                    "Could not reach LiveKit Egress API at "
                            + egressBaseUrl
                            + " ("
                            + e.getMessage()
                            + ")",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LiveKitEgressException("Interrupted while calling LiveKit Egress API", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new LiveKitEgressException(
                    "LiveKit Egress "
                            + method
                            + " returned HTTP "
                            + response.statusCode()
                            + ": "
                            + response.body());
        }

        try {
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode egressIdNode = json.get("egress_id");
            return egressIdNode != null ? egressIdNode.asString(null) : null;
        } catch (JacksonException e) {
            throw new LiveKitEgressException(
                    "Could not parse LiveKit Egress " + method + " response: " + response.body(),
                    e);
        }
    }

    /**
     * Object keys must not carry path separators or other characters a hostile room/tenant name
     * could smuggle in.
     */
    private static String sanitizeKeySegment(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    public record EgressStartResult(String egressId, String objectKey) {}
}
