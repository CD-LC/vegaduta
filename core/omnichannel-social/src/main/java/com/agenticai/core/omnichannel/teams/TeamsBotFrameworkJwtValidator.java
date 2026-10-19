package com.agenticai.core.omnichannel.teams;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Validates the {@code Authorization: Bearer} JWT Microsoft's Bot Framework attaches to every
 * inbound activity POST - the Teams channel's equivalent of Telegram's {@code
 * X-Telegram-Bot-Api-Secret-Token} check or Slack's signing secret, except Microsoft's mechanism is
 * asymmetric: tokens are issued by {@code https://api.botframework.com}, RS256-signed by the keys
 * published at {@code https://login.botframework.com/v1/.well-known/keys}, and carry the receiving
 * bot's Microsoft App ID as their audience.
 *
 * <p>The audience check is what makes the path-scoped tenant lookup safe: {@code
 * /webhook/teams/{appId}} resolves the tenant BEFORE signature verification (the same
 * "self-defeating to forge" pattern Slack/Twilio use - a caller who knows another tenant's appId
 * still cannot mint a token for it without that bot's registration), then this validator proves the
 * call came from Bot Framework and was meant for exactly this bot.
 *
 * <p>{@link NimbusJwtDecoder} is already on this module's classpath via the {@code security}
 * dependency (spring-boot-starter-oauth2-resource-server); its internal JWK cache means the
 * Microsoft key set is fetched once per process, not per message.
 */
@Component
public class TeamsBotFrameworkJwtValidator {

    private static final String JWKS_URI = "https://login.botframework.com/v1/.well-known/keys";
    private static final String EXPECTED_ISSUER = "https://api.botframework.com";

    private final JwtDecoder decoder;
    private final TeamsServiceUrlPolicy serviceUrlPolicy;

    @org.springframework.beans.factory.annotation.Autowired
    public TeamsBotFrameworkJwtValidator(TeamsServiceUrlPolicy serviceUrlPolicy) {
        this(buildDecoder(), serviceUrlPolicy);
    }

    /** Test seam: a decoder that does not fetch Microsoft's JWKS. */
    TeamsBotFrameworkJwtValidator(JwtDecoder decoder, TeamsServiceUrlPolicy serviceUrlPolicy) {
        this.decoder = decoder;
        this.serviceUrlPolicy = serviceUrlPolicy;
    }

    /**
     * @return {@code true} only if the token's signature verifies against Microsoft's published
     *     keys, it is unexpired, issued by {@code api.botframework.com}, addressed (aud) to {@code
     *     appId}, AND (SEC-047) its {@code serviceurl} claim equals the activity's {@code
     *     serviceUrl}, which must itself be an https URL on the Bot Framework host allowlist
     *     ({@link TeamsServiceUrlPolicy}). Bot Framework always sets {@code serviceurl} on channel
     *     tokens, so a token without it is rejected (the Bot Framework Emulator is not supported on
     *     this endpoint; there is no dev bypass). Any rejection reason maps to {@code false} - the
     *     caller rejects the webhook either way, no detail is leaked to the caller.
     */
    public boolean isValid(String bearerToken, String appId, String activityServiceUrl) {
        if (bearerToken == null || bearerToken.isBlank() || appId == null) {
            return false;
        }
        try {
            Jwt jwt = decoder.decode(bearerToken);
            Object aud = jwt.getClaim(JwtClaimNames.AUD);
            boolean audienceOk =
                    aud instanceof List<?> audience ? audience.contains(appId) : appId.equals(aud);
            if (!audienceOk) {
                return false;
            }
            return serviceUrlBound(jwt, activityServiceUrl);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SEC-047: without this binding a genuine Bot Framework token for this bot (e.g. replayed from
     * any conversation) could be paired with an attacker-chosen activity {@code serviceUrl}, and
     * the bot's AAD token would then be sent there on the reply.
     */
    private boolean serviceUrlBound(Jwt jwt, String activityServiceUrl) {
        Object claim = jwt.getClaims().get("serviceurl");
        if (claim == null) {
            claim = jwt.getClaims().get("serviceUrl");
        }
        if (!(claim instanceof String claimed) || claimed.isBlank()) {
            return false;
        }
        String normalisedClaim = TeamsServiceUrlPolicy.normalise(claimed);
        String normalisedActivity = TeamsServiceUrlPolicy.normalise(activityServiceUrl);
        if (normalisedClaim == null || !normalisedClaim.equals(normalisedActivity)) {
            return false;
        }
        return serviceUrlPolicy.isAllowed(activityServiceUrl);
    }

    private static JwtDecoder buildDecoder() {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
        // createDefaultWithIssuer covers exp/nbf (timestamp validator) + iss.
        nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(EXPECTED_ISSUER));
        return nimbus;
    }
}
