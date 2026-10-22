package com.agenticai.core.voice;

import com.agenticai.core.json.JsonMappers;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Signs LiveKit access tokens directly per LiveKit's documented JWT token spec (HS256, {@code
 * iss}=API key, {@code sub}=participant identity, a {@code video} claim carrying the room grant) -
 * see this module's pom.xml javadoc for why this doesn't pull in LiveKit's own server SDK.
 *
 * <p>Every token grants exactly one room, scoped to whichever agent/session the caller was
 * authenticated for by {@link com.agenticai.core.voice.VoiceCallController#issueToken} - never a
 * platform-wide grant.
 */
@Component
public class LiveKitTokenService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitTokenService.class);

    private final ObjectMapper objectMapper = JsonMappers.plain();

    private final String apiKey;
    private final String apiSecret;
    private final String url;
    private final long ttlSeconds;

    public LiveKitTokenService(
            @Value("${livekit.api-key:}") String apiKey,
            @Value("${livekit.api-secret:}") String apiSecret,
            @Value("${livekit.url:ws://localhost:7880}") String url,
            @Value("${livekit.token-ttl-seconds:3600}") long ttlSeconds) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.url = url;
        this.ttlSeconds = ttlSeconds;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
    }

    /**
     * The browser-facing LiveKit WebSocket URL (e.g. {@code wss://livekit.staging.example.com})
     * this token is valid against.
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param identity unique per-participant id within the room (this platform uses {@code
     *     "<tenantId>:<subject>"} - see the controller).
     * @param roomName the LiveKit room this token grants join access to.
     * @param participantName display name LiveKit clients can show for this participant.
     */
    public String issueToken(String identity, String roomName, String participantName) {
        // Explicit (String) cast: a bare null is ambiguous between this class's
        // (String callerAccessToken) and (Map<String,Object> metadata) overloads
        // since both were added independently. Cast preserves this wrapper's
        // original behavior - no caller access token, no metadata claim.
        return issueToken(identity, roomName, participantName, (String) null);
    }

    /**
     * Same as {@link #issueToken(String, String, String)}, plus a way to hand the caller's own
     * bearer token to whichever server-side participant ends up sharing this room with them - the
     * {@code voice-agent} worker (LiveKit Agents, Python; see {@code deploy/docker-compose.yml}'s
     * {@code voice-agent} service and its automatic-dispatch comment), which needs to call back
     * into {@code /api/agents/{agentId}/chat} as this same user once it joins.
     *
     * <p>Carried via the JWT's top-level {@code metadata} claim - LiveKit's documented access-token
     * contract (confirmed against <a
     * href="https://docs.livekit.io/frontends/reference/tokens-grants/">LiveKit's tokens &amp;
     * grants reference</a>, which lists {@code metadata} as a distinct top-level claim,
     * "Participant metadata", separate from the {@code video} grant this class already builds)
     * applies that string as the participant's initial {@code metadata} the moment they join - no
     * race, no extra grant needed. This was chosen over having the browser call {@code
     * room.localParticipant.setMetadata(...)} (livekit-client 2.21.0, confirmed against the
     * installed package's {@code LocalParticipant.d.ts}) after {@code room.connect()}: that path
     * (a) requires the token to separately grant {@code canUpdateOwnMetadata} before the client is
     * even allowed to call it, (b) is a second server round-trip racing against however fast the
     * worker picks up the job, and (c) still needs the browser to hold and transmit the token
     * itself, whereas the browser's token-issuance call ({@code POST
     * /api/voice/agents/{agentId}/token}, see {@link
     * com.agenticai.core.voice.VoiceCallController#issueToken}) is already authenticated with that
     * exact bearer token - core simply forwards it server-side, nothing new for the browser to
     * plumb.
     *
     * <p>{@code callerAccessToken} is never logged (see {@code VoiceCallController#issueToken}'s
     * log line, which only logs identity/room/agentId) - it is short-lived (15 min, {@code
     * accessTokenLifespan} in the realm config) and scoped to this one user, but it is still a
     * bearer credential and is treated like the other tokens this codebase is careful not to log.
     *
     * @param callerAccessToken the caller's own bearer token, or {@code null}/blank if there isn't
     *     one (e.g. security profile off, or an anonymous caller) - in which case no {@code
     *     metadata} claim is added at all, same shape as before this overload existed.
     */
    public String issueToken(
            String identity, String roomName, String participantName, String callerAccessToken) {
        Map<String, Object> metadata =
                (callerAccessToken == null || callerAccessToken.isBlank())
                        ? null
                        : Map.of("coreAccessToken", callerAccessToken);
        return issueToken(identity, roomName, participantName, metadata);
    }

    /**
     * Same as {@link #issueToken(String, String, String, String)}, generalized to an arbitrary
     * metadata map instead of a single {@code coreAccessToken} string - the public agent-share
     * surface uses this to carry {@code {"shareId": ..., "shareSessionKey": ...}} instead of a
     * bearer token (an anonymous share viewer has no JWT to forward), so {@code voice-agent}'s
     * worker can tell the two room kinds apart and call back into the appropriate core endpoint -
     * see {@code AgentShareVoiceController}'s javadoc for the full share-scoped flow. The {@code
     * coreAccessToken} overload above is unchanged and remains the entry point for every existing
     * authenticated caller.
     *
     * @param metadata arbitrary key/value pairs to serialize as the participant's initial {@code
     *     metadata}, or {@code null}/empty for no metadata claim at all.
     */
    public String issueToken(
            String identity,
            String roomName,
            String participantName,
            Map<String, Object> metadata) {
        if (!isConfigured()) {
            throw new LiveKitNotConfiguredException(
                    "Self-hosted voice calling is not configured - set LIVEKIT_API_KEY/LIVEKIT_API_SECRET "
                            + "(deploy/.env) and restart core.");
        }
        try {
            Instant now = Instant.now();
            Map<String, Object> videoGrant = new LinkedHashMap<>();
            videoGrant.put("room", roomName);
            videoGrant.put("roomJoin", true);
            videoGrant.put("canPublish", true);
            videoGrant.put("canSubscribe", true);
            videoGrant.put("canPublishData", true);

            JWTClaimsSet.Builder claimsBuilder =
                    new JWTClaimsSet.Builder()
                            .issuer(apiKey)
                            .subject(identity)
                            .claim("name", participantName)
                            .issueTime(Date.from(now))
                            .notBeforeTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                            .jwtID(UUID.randomUUID().toString())
                            .claim("video", videoGrant);

            String participantMetadata = buildParticipantMetadata(metadata);
            if (participantMetadata != null) {
                claimsBuilder.claim("metadata", participantMetadata);
            }

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsBuilder.build());
            jwt.sign(new MACSigner(apiSecret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (LiveKitNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sign LiveKit access token: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes the participant-metadata JSON string carried in the JWT's {@code metadata} claim
     * (see {@link #issueToken(String, String, String, Map)}). Deliberately fails closed - a
     * serialization failure (there is no realistic way a {@code Map.of(...)} of plain strings fails
     * to serialize, but this is a credential-bearing path for the {@code coreAccessToken} case)
     * drops the metadata claim rather than blowing up call setup, logged without the metadata
     * values.
     */
    private String buildParticipantMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn(
                    "Failed to serialize LiveKit participant metadata - issuing token without it: {}",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Signs a short-lived JWT for calling LiveKit's Egress (Twirp) API server-to-server - {@link
     * com.agenticai.core.voice.LiveKitEgressService} is the only caller. Same
     * HS256/api-key/api-secret pair as {@link #issueToken}, but a deliberately different, narrower
     * grant: LiveKit's Egress endpoints (StartRoomCompositeEgress/StopEgress/etc, see <a
     * href="https://docs.livekit.io/reference/other/egress/api/">LiveKit's Egress API docs</a>)
     * authorize on the {@code roomRecord} video-grant flag, not {@code roomJoin} - this token
     * grants no ability to join or publish to any room itself, only to start/stop recordings, and
     * (unlike {@link #issueToken}) is not scoped to one room name since a single server-side caller
     * may need to manage egress for many concurrent calls.
     */
    public String issueEgressAuthToken() {
        if (!isConfigured()) {
            throw new LiveKitNotConfiguredException(
                    "Self-hosted voice calling is not configured - set LIVEKIT_API_KEY/LIVEKIT_API_SECRET "
                            + "(deploy/.env) and restart core.");
        }
        try {
            Instant now = Instant.now();
            Map<String, Object> videoGrant = new LinkedHashMap<>();
            videoGrant.put("roomRecord", true);

            JWTClaimsSet claims =
                    new JWTClaimsSet.Builder()
                            .issuer(apiKey)
                            .subject("core-egress-caller")
                            .issueTime(Date.from(now))
                            .notBeforeTime(Date.from(now))
                            // Deliberately short-lived - this token is minted fresh for
                            // every Egress API call (see LiveKitEgressService), never
                            // handed to a browser, so there's no reason to match
                            // issueToken's longer ttlSeconds (that value exists to cover
                            // a browser call's whole duration).
                            .expirationTime(Date.from(now.plusSeconds(60)))
                            .jwtID(UUID.randomUUID().toString())
                            .claim("video", videoGrant)
                            .build();

            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(apiSecret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (LiveKitNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to sign LiveKit Egress auth token: " + e.getMessage(), e);
        }
    }
}
