package com.agenticai.core.omnichannel.x;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.x.dto.XAppRegistrationRequest;
import com.agenticai.core.omnichannel.x.dto.XConnectionResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.oauth.OAuthStateBindingException;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-step BYOK-then-OAuth activation for a tenant's own X (Twitter) connection - see {@code
 * XCredential}'s javadoc for why this is a hybrid of the BYOK pattern (Twilio/Meta/Slack/Discord)
 * and the OAuth-redirect pattern ({@code GoogleOAuthService}), rather than purely either one.
 *
 * <p><b>What a tenant does, end to end</b>: create a Project + App at developer.x.com (requires at
 * least the paid Basic tier for DM access), under User authentication settings enable OAuth 2.0
 * with type "Confidential client", add this platform's fixed callback URL (see {@code
 * app.x.oauth.redirect-uri}) as a Callback URI, request the {@code dm.read}, {@code dm.write},
 * {@code tweet.read}, {@code users.read}, and {@code offline.access} scopes, then copy the OAuth
 * 2.0 Client ID and Client Secret into {@link #registerApp}. Then, in the console, click "Connect X
 * Account" ({@link #authorizeUrl}) and complete the consent screen as whichever X account should
 * send/receive these DMs.
 *
 * <p><b>How {@code state} carries both tenant identity AND the PKCE verifier across the
 * redirect</b>: mirrors {@code GoogleOAuthService}'s encrypted-state technique exactly, just with
 * one more field packed in - {@code schema|codeVerifier|issuedAtEpochSeconds}, AES-256-GCM
 * encrypted so a tampered value fails to decrypt at all and the embedded timestamp caps replay
 * window, same as Google's.
 */
@Service
public class XOAuthService {

    private static final Logger log = LoggerFactory.getLogger(XOAuthService.class);
    private static final long STATE_MAX_AGE_SECONDS =
            600; // 10 minutes - ample for a user to complete X's consent screen.
    private static final String SCOPES = "dm.read dm.write tweet.read users.read offline.access";

    private final XCredentialRepository repository;
    private final XOAuthClient client;
    private final XApiClient apiClient;
    private final EncryptionService encryptionService;
    private final TenantLimits tenantLimits;
    private final String redirectUri;
    private final OAuthStateBinding stateBinding;

    /**
     * Provider key for {@link OAuthStateBinding} - also the callback path segment ({@code
     * /oauth/x/callback}).
     */
    public static final String BINDING_PROVIDER = "x";

    /** Test constructor: a private in-memory binding store with the browser cookie optional. */
    public XOAuthService(
            XCredentialRepository repository,
            XOAuthClient client,
            XApiClient apiClient,
            EncryptionService encryptionService,
            String redirectUri) {
        this(
                repository,
                client,
                apiClient,
                encryptionService,
                redirectUri,
                new OAuthStateBinding(false, java.time.Clock.systemUTC()));
    }

    /**
     * Test constructor with an explicit state binding and no plan gate ({@code tenantLimits} null).
     */
    public XOAuthService(
            XCredentialRepository repository,
            XOAuthClient client,
            XApiClient apiClient,
            EncryptionService encryptionService,
            String redirectUri,
            OAuthStateBinding stateBinding) {
        this(repository, client, apiClient, encryptionService, null, redirectUri, stateBinding);
    }

    @Autowired
    public XOAuthService(
            XCredentialRepository repository,
            XOAuthClient client,
            XApiClient apiClient,
            EncryptionService encryptionService,
            TenantLimits tenantLimits,
            @Value("${app.x.oauth.redirect-uri:}") String redirectUri,
            OAuthStateBinding stateBinding) {
        this.stateBinding = stateBinding;
        this.repository = repository;
        this.client = client;
        this.apiClient = apiClient;
        this.encryptionService = encryptionService;
        this.tenantLimits = tenantLimits;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return !redirectUri.isBlank();
    }

    /**
     * Step 1 - store the tenant's own X Developer App credentials. Does not touch X's API at all
     * (nothing to validate yet without a user to authorize).
     */
    @Transactional
    public XConnectionResponse registerApp(XAppRegistrationRequest request) {
        // Plan gate - third-party channel connections are a paid-tier
        // entitlement (plan_limits.external_channels_allowed). Gated here at
        // step 1 rather than only at authorizeUrl so the plan answer is the
        // first thing a tenant sees, before any OAuth round-trip.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null
                && tenantLimits != null
                && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include external channel connections - upgrade your plan to connect X.");
        }
        String encryptedSecret = encryptionService.encrypt(request.clientSecret());
        XCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reregisterApp(request.clientId(), encryptedSecret);
                                    return existing;
                                })
                        .orElseGet(() -> new XCredential(request.clientId(), encryptedSecret));
        return XConnectionResponse.from(repository.save(credential));
    }

    @Transactional(readOnly = true)
    public Optional<XConnectionResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(XConnectionResponse::from);
    }

    /**
     * Step 2, part A - called from an authenticated console request; the current tenant + a fresh
     * PKCE verifier both get encoded into {@code state}.
     */
    @Transactional(readOnly = true)
    public String authorizeUrl() {
        return authorizeRedirect().url();
    }

    /**
     * {@link #authorizeUrl} plus the SEC-018 browser-binding cookie the controller sets on its
     * response.
     */
    @Transactional(readOnly = true)
    public AuthorizeRedirect authorizeRedirect() {
        if (!isConfigured()) {
            throw new XNotConfiguredException();
        }
        XCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .orElseThrow(XNotConfiguredException::new);
        XOAuthClient.PkcePair pkce = client.newPkcePair();
        String schema = TenantSchemaResolver.currentSchemaName();
        String initiator = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        OAuthStateBinding.Issued issued =
                stateBinding.issue(BINDING_PROVIDER, schema, null, initiator);
        String state = encodeState(schema, pkce.verifier(), issued.nonce());
        return new AuthorizeRedirect(
                client.buildAuthorizationUrl(
                        credential.getClientId(), redirectUri, SCOPES, state, pkce.challenge()),
                issued.cookie());
    }

    /**
     * SEC-018: verify and CONSUME (single use) the server-side binding of a decoded callback state;
     * must be called before {@link #connect}.
     *
     * @throws XOAuthStateException if the nonce is unknown/used/expired, bound to another tenant,
     *     or the binding cookie (when present, or when required by config) does not match
     */
    public void verifyBinding(DecodedState decoded, String bindingCookie) {
        try {
            stateBinding.consume(
                    BINDING_PROVIDER, decoded.nonce(), decoded.tenantSchema(), null, bindingCookie);
        } catch (OAuthStateBindingException e) {
            throw new XOAuthStateException(
                    "This connection attempt is no longer valid - please start connecting again from the console. ("
                            + e.getMessage()
                            + ")");
        }
    }

    /**
     * @return the tenant schema and PKCE verifier this state was minted for. @throws
     *     XOAuthStateException if tampered, malformed, or expired.
     */
    public DecodedState decodeState(String state) {
        String decrypted;
        try {
            decrypted = encryptionService.decrypt(state);
        } catch (Exception e) {
            throw new XOAuthStateException(
                    "Could not verify the OAuth state - please try connecting again.");
        }
        // SEC-018: schema|codeVerifier|bindingNonce|epoch. The pre-SEC-018 3-field form is refused:
        // it
        // carries no binding and could be replayed from any browser.
        String[] parts = decrypted.split("\\|");
        if (parts.length != 4) {
            throw new XOAuthStateException("Malformed OAuth state.");
        }
        long issuedAtEpochSeconds;
        try {
            issuedAtEpochSeconds = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new XOAuthStateException("Malformed OAuth state.");
        }
        if (Instant.now().getEpochSecond() - issuedAtEpochSeconds > STATE_MAX_AGE_SECONDS) {
            throw new XOAuthStateException(
                    "This connection attempt expired - please try connecting again.");
        }
        return new DecodedState(parts[0], parts[1], parts[2]);
    }

    private String encodeState(String schema, String codeVerifier, String nonce) {
        String payload =
                schema + "|" + codeVerifier + "|" + nonce + "|" + Instant.now().getEpochSecond();
        return encryptionService.encrypt(payload);
    }

    /**
     * Step 2, part B - must be called with {@code TenantContext} already set to the schema {@link
     * #decodeState} returned.
     */
    @Transactional
    public String connect(String code, String codeVerifier) {
        XCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .orElseThrow(XNotConfiguredException::new);
        String clientSecret = encryptionService.decrypt(credential.getEncryptedClientSecret());

        XOAuthClient.TokenResponse tokens =
                client.exchangeCode(
                        credential.getClientId(), clientSecret, redirectUri, code, codeVerifier);
        if (tokens.refreshToken() == null) {
            throw new XApiException(
                    "X did not return a refresh token - check that the offline.access scope is enabled on this app and try again.");
        }
        XOAuthClient.MeResponse me = client.fetchMe(tokens.accessToken());
        credential.connect(
                me.id(), me.username(), encryptionService.encrypt(tokens.refreshToken()));
        repository.save(credential);

        // Seed the poll cursor to "now" - see XApiClient.fetchLatestEventId's javadoc for why:
        // without this, the
        // first poll cycle would treat this account's entire DM history as brand-new and reply to
        // all of it.
        try {
            String latestEventId = apiClient.fetchLatestEventId(tokens.accessToken());
            credential.advancePollCursor(latestEventId);
            repository.save(credential);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not seed the X DM poll cursor at connect time (will seed on first poll instead): {}",
                    e.getMessage());
        }

        return me.username();
    }

    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            credential.revoke();
                            repository.save(credential);
                        });
    }

    /**
     * @throws XNotConfiguredException if no app is registered yet.
     * @throws XNotConnectedException if an app is registered but no account has completed OAuth
     *     yet.
     */
    @Transactional
    public String currentAccessToken() {
        XCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .orElseThrow(XNotConfiguredException::new);
        if (credential.getStatus() != XCredentialStatus.ACTIVE
                || credential.getEncryptedRefreshToken() == null) {
            throw new XNotConnectedException();
        }
        String clientSecret = encryptionService.decrypt(credential.getEncryptedClientSecret());
        String refreshToken = encryptionService.decrypt(credential.getEncryptedRefreshToken());
        XOAuthClient.TokenResponse refreshed =
                client.refreshAccessToken(credential.getClientId(), clientSecret, refreshToken);
        if (refreshed.refreshToken() != null) {
            // X rotates the refresh token on every use - see XCredential's javadoc. Must persist
            // immediately.
            credential.rotateRefreshToken(encryptionService.encrypt(refreshed.refreshToken()));
            repository.save(credential);
        }
        return refreshed.accessToken();
    }

    @Transactional(readOnly = true)
    public Optional<XCredential> activeCredential() {
        return repository.findFirstByStatusOrderByCreatedAtAsc(XCredentialStatus.ACTIVE);
    }

    /**
     * Always re-fetches the row itself rather than accepting/saving a caller-held {@link
     * XCredential} instance - {@code XDmPollingJob} calls {@link #currentAccessToken} first (which
     * may have just rotated and persisted a new refresh token) and must not then save back a stale,
     * pre-rotation copy of the same row, which is exactly what would happen if it saved its own
     * earlier-loaded reference instead of going through this method.
     */
    @Transactional
    public void advancePollCursor(String newCursor) {
        repository
                .findFirstByStatusOrderByCreatedAtAsc(XCredentialStatus.ACTIVE)
                .ifPresent(
                        credential -> {
                            credential.advancePollCursor(newCursor);
                            repository.save(credential);
                        });
    }

    public record DecodedState(String tenantSchema, String codeVerifier, String nonce) {}

    /** An authorization URL plus the SEC-018 browser-binding cookie. */
    public record AuthorizeRedirect(String url, ResponseCookie bindingCookie) {}
}
