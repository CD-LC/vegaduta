package com.agenticai.core.google;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.oauth.OAuthStateBinding;
import com.agenticai.core.security.oauth.OAuthStateBindingException;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google Workspace integration - one platform-level OAuth app, not BYOK.
 *
 * <p><b>Why platform-level, unlike Twilio/Meta manual BYOK</b>: Twilio/Meta
 * BYOK works because each tenant registers their own developer app and
 * pastes API keys into a form - no browser redirect involved. Google's
 * consumer-facing OAuth consent screen requires the redirect URI to be
 * pre-registered on the OAuth client in Google Cloud Console; asking every
 * tenant to create their own Google Cloud project, configure an OAuth
 * consent screen, and register this platform's callback URL themselves
 * would be a dramatically worse signup experience for a Drive/Gmail/
 * Calendar/Sheets connection than clicking one "Connect Google" button -
 * the same tradeoff Meta's Embedded Signup makes over manual BYOK, just
 * without a manual-form alternative at all here, since Google (unlike Meta)
 * doesn't offer BYOK-friendly long-lived service credentials for personal
 * Workspace/consumer accounts.
 *
 * <p><b>Per-end-user credentials (V215)</b>: until V215 this module was
 * strictly one credential per tenant, connected by an admin - and because
 * {@code GoogleScopes.REQUESTED} included {@code gmail.readonly} AND {@code
 * gmail.send}, and the tool layer hands all five Google tools to every agent
 * in the tenant automatically, that meant any member who could chat with any
 * agent could read the admin's inbox and send mail as the admin. Now there
 * are two kinds of row, and one resolution rule used everywhere:
 *
 * <ul>
 *   <li><b>Tenant-wide</b> ({@code subject_key IS NULL}) - the legacy shape,
 *   still connected/disconnected only by an admin through the original
 *   endpoints. Every pre-V215 row is this kind.</li>
 *   <li><b>Personal</b> ({@code subject_key = <Keycloak sub>}) - one row per
 *   end user, connected by that user through {@link #personalAuthorizeUrl}
 *   and removable only by that user through {@link #disconnectPersonal}.</li>
 * </ul>
 *
 * <p><b>Resolution rule</b> ({@link #effectiveActiveCredentialFor}): the
 * caller's own ACTIVE personal row if one exists, else the tenant-wide ACTIVE
 * row. A tenant where nobody ever connects a personal account therefore
 * resolves exactly the row it resolved before V215, on every path - that
 * fallback IS the backward-compatibility guarantee, which is why {@code
 * subject_key} is nullable rather than backfilled.
 *
 * <p><b>Which subject drives resolution</b>: {@code
 * TenantContext.getSubject()} on the calling thread. On a console request
 * that is the JWT's Keycloak {@code sub} - the identical value the agent
 * memory subsystem keys {@code subjectKey} on, so "who is asking" means the
 * same thing in both places by construction. Inside an agent tool call it is
 * the value {@code TenantContextPropagatingToolCallback} captured on the
 * request thread and re-set around the reactive hop, so per-user resolution
 * works there with no change to the tool layer. Synthetic subjects
 * ({@code "telegram:<botId>"} webhooks, {@code "system:*"} schedulers,
 * {@code "demo:*"} sandboxes...) can never have completed an OAuth consent,
 * so they miss the personal leg and land on the tenant-wide fallback - which
 * is the only credential that can be right for them. One deliberate behavior
 * change falls out for background work that runs under a restored REAL
 * subject (Temporal workflow nodes and agent jobs both restore the original
 * requester's {@code sub}): those now use the requester's personal Google
 * account when the requester has connected one. That is intended - a job a
 * person queued should act as that person acts interactively, and anyone who
 * never connects a personal account sees no change at all.
 *
 * <p><b>How the OAuth `state` carries tenant identity across the redirect</b>:
 * unlike an inbound webhook (Twilio/Meta), which must resolve
 * "which tenant is this?" from an external identifier it's never seen
 * before, here the tenant is already known and authenticated at the moment
 * {@link #authorizeUrl()} is called (a normal JWT-bearing console request).
 * So instead of a cross-tenant lookup table, the current tenant's already-
 * sanitized schema name (see {@code TenantSchemaResolver#currentSchemaName})
 * is encrypted (AES-256-GCM via the same {@link EncryptionService} that
 * protects the refresh token itself) together with a timestamp into the
 * `state` value Google round-trips back verbatim. {@link #decodeConnectState}
 * decrypts it on the way back in {@code GoogleOAuthController#callback} -
 * GCM's authentication tag means a tampered state fails to decrypt at all,
 * and the embedded timestamp caps how long a captured state value could
 * ever be reused for (see {@link GoogleOAuthStateCodec}). Since V215 a
 * personal connect additionally carries the consenting user's subject inside
 * that same encrypted payload - it MUST travel through the state, because the
 * callback request comes from Google with no JWT and runs under a synthetic
 * {@code TenantContext} subject, so the state is the only authenticated
 * channel that can say who consented.
 *
 * <p><b>No access token is ever persisted</b> - {@link #currentAccessToken}
 * exchanges the stored (encrypted) refresh token for a fresh ~1-hour access
 * token on every call. Simpler than caching with expiry tracking, and
 * Google's token endpoint has no meaningful rate limit at this call volume;
 * a future optimization could cache per-request instead of per-tool-call if
 * that ever becomes measurably slow.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);

    private final GoogleCredentialRepository repository;
    private final GoogleOAuthClient client;
    private final EncryptionService encryptionService;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final OAuthStateBinding stateBinding;
    private final GoogleOAuthStateCodec stateCodec;
    /** Personal installs: Desktop-app client, any-port loopback redirect, PKCE. Everything else: web application. */
    private final boolean loopbackMode;
    /** PKCE verifiers by state nonce. In memory on purpose: loopback mode is the one-process personal install. */
    private final java.util.concurrent.ConcurrentHashMap<String, PendingPkce> pkce =
        new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingPkce(String verifier, long expiresAtMillis) {
    }

    private static final long PKCE_TTL_MILLIS = 10 * 60 * 1000L;

    /** Provider key for {@link OAuthStateBinding} - also the callback path segment ({@code /oauth/google/callback}). */
    public static final String BINDING_PROVIDER = "google";

    /** Test/legacy constructor: a private in-memory binding store with the browser cookie optional. */
    public GoogleOAuthService(
        GoogleCredentialRepository repository,
        GoogleOAuthClient client,
        EncryptionService encryptionService,
        String clientId,
        String clientSecret,
        String redirectUri
    ) {
        this(repository, client, encryptionService, clientId, clientSecret, redirectUri,
            new OAuthStateBinding(false, java.time.Clock.systemUTC()));
    }

    public GoogleOAuthService(
        GoogleCredentialRepository repository,
        GoogleOAuthClient client,
        EncryptionService encryptionService,
        String clientId,
        String clientSecret,
        String redirectUri,
        OAuthStateBinding stateBinding
    ) {
        this(repository, client, encryptionService, clientId, clientSecret, redirectUri, stateBinding, "web");
    }

    @Autowired
    public GoogleOAuthService(
        GoogleCredentialRepository repository,
        GoogleOAuthClient client,
        EncryptionService encryptionService,
        @Value("${app.google.oauth.client-id:}") String clientId,
        @Value("${app.google.oauth.client-secret:}") String clientSecret,
        @Value("${app.google.oauth.redirect-uri:}") String redirectUri,
        OAuthStateBinding stateBinding,
        @Value("${app.google.oauth.mode:web}") String mode
    ) {
        this.loopbackMode = "loopback".equalsIgnoreCase(mode == null ? "" : mode.trim());
        this.stateBinding = stateBinding;
        this.repository = repository;
        this.client = client;
        this.encryptionService = encryptionService;
        this.stateCodec = new GoogleOAuthStateCodec(encryptionService);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return hasCredentials() && (!loopbackMode || isLoopbackRedirect());
    }

    private boolean hasCredentials() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    /** Why {@link #isConfigured} is false although a client is present: a Desktop-app client only redirects to loopback. */
    public boolean redirectNotLoopback() {
        return loopbackMode && hasCredentials() && !isLoopbackRedirect();
    }

    public boolean loopbackMode() {
        return loopbackMode;
    }

    private boolean isLoopbackRedirect() {
        try {
            String host = java.net.URI.create(redirectUri).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The callback URL Google must be told about (shown to a self-hoster registering their own client). */
    public String redirectUri() {
        return redirectUri;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new GoogleOAuthNotConfiguredException();
        }
    }

    /**
     * What the encrypted OAuth {@code state} carried across the redirect:
     * which tenant schema the consent belongs to, which end user it belongs
     * to for a personal connect ({@code null} means tenant-wide, both for
     * pre-V215 states still in flight and for admin connects minted today),
     * and which consent "purpose" the URL was minted for ({@code null} means
     * a base connect - the value {@link GoogleOAuthController#callback} uses
     * to tell an incremental-scope callback apart from a base one, since both
     * land on the same endpoint).
     */
    public record DecodedState(String schema, String subjectKey, String purpose, String nonce) {

        public DecodedState(String schema, String subjectKey, String purpose) {
            this(schema, subjectKey, purpose, null);
        }
    }

    /** An authorization URL plus the SEC-018 browser-binding cookie the caller should set on its response. */
    public record AuthorizeRedirect(String url, ResponseCookie bindingCookie) {
    }

    /**
     * State-purpose marker for the chained {@link GoogleScopes#YOUTUBE_READONLY}
     * incremental consent - see that scope's javadoc for why it can't ride in
     * {@link GoogleScopes#REQUESTED}. A callback arriving with this purpose
     * means the base connect already succeeded, so its failure/decline must
     * surface as "connected" (the tenant's base connection is real and
     * usable), never as a connect failure.
     */
    static final String PURPOSE_YOUTUBE_READONLY = "youtube-readonly";

    /**
     * State-purpose marker for the {@link GoogleScopes#YOUTUBE_UPLOAD}
     * incremental consent ({@link #youtubeUploadAuthorizeUrl}). Same role as
     * {@link #PURPOSE_YOUTUBE_READONLY}: keeps that callback from being
     * mistaken for a base connect and chained into another consent screen.
     */
    static final String PURPOSE_YOUTUBE_UPLOAD = "youtube-upload";

    /**
     * Tenant-wide connect - the original admin-gated flow, unchanged. Called
     * from an authenticated console request; the current {@code TenantContext}
     * schema is what gets encoded into `state`, with NO subject, so the
     * resulting credential is the {@code subject_key IS NULL} fallback row
     * every member of the tenant shares.
     */
    public String authorizeUrl() {
        return authorizeRedirect().url();
    }

    /** {@link #authorizeUrl} plus the SEC-018 binding cookie. */
    public AuthorizeRedirect authorizeRedirect() {
        requireConfigured();
        String schema = TenantSchemaResolver.currentSchemaName();
        Minted minted = mint(schema, null, null);
        return new AuthorizeRedirect(
            authUrl(GoogleScopes.REQUESTED, minted, null), minted.cookie());
    }

    /**
     * Personal connect - new with V215, reachable by ANY authenticated tenant
     * member (the route deliberately has no admin gate; see {@code
     * GoogleOAuthController}). Encodes the caller's own Keycloak {@code sub}
     * into the state so the JWT-less callback can attribute the consent to the
     * right person.
     *
     * @throws IllegalStateException if there is no usable subject on the
     *     calling thread - impossible on the authenticated route this backs
     *     ({@code TenantContextFilter} always sets the JWT's {@code sub}), so
     *     this is a fail-fast guard against some future internal caller
     *     minting a personal consent URL for nobody, or for a subject that
     *     would corrupt the {@code |}-delimited state payload.
     */
    public String personalAuthorizeUrl() {
        return personalAuthorizeRedirect().url();
    }

    /** {@link #personalAuthorizeUrl} plus the SEC-018 binding cookie. */
    public AuthorizeRedirect personalAuthorizeRedirect() {
        requireConfigured();
        String schema = TenantSchemaResolver.currentSchemaName();
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("A personal Google connect requires an authenticated user subject on the current thread.");
        }
        if (subject.indexOf('|') >= 0) {
            // Keycloak subs are UUIDs and can never contain '|'; anything that
            // does would break the state payload's field separator.
            throw new IllegalStateException("Refusing to encode a subject containing '|' into the OAuth state.");
        }
        Minted minted = mint(schema, subject, null);
        return new AuthorizeRedirect(
            authUrl(GoogleScopes.REQUESTED, minted, null), minted.cookie());
    }

    /**
     * Incremental consent for {@link GoogleScopes#YOUTUBE_UPLOAD} - tenant-
     * wide only (admin-gated, same as {@link #authorizeUrl}), deliberately
     * not offered as a personal connect: publishing to the tenant's YouTube
     * channel is an admin-level decision, not something any member should be
     * able to opt the tenant into. Requests ONLY the upload scope, not the
     * full {@link GoogleScopes#REQUESTED} bundle - {@code
     * include_granted_scopes=true} (already set on every URL {@link
     * GoogleOAuthClient#buildAuthorizationUrl} builds) is what makes this
     * additive rather than a re-grant: Google returns a token whose {@code
     * scope} covers the union of what was already granted plus this one, and
     * {@link #connect} stores that union verbatim - no merging logic needed
     * here or there. See {@link GoogleScopes#YOUTUBE_UPLOAD}'s javadoc for
     * why this scope is split out from the base connect at all, and for the
     * external Google verification step this still needs before it works for
     * real (non-test-user) tenants.
     *
     * @throws GoogleNotConnectedException if the tenant has no active
     *     tenant-wide Google connection yet - upload scope is additive to an
     *     existing connection, not a way to create the first one.
     */
    public String youtubeUploadAuthorizeUrl() {
        return youtubeUploadAuthorizeRedirect().url();
    }

    /** {@link #youtubeUploadAuthorizeUrl} plus the SEC-018 binding cookie. */
    public AuthorizeRedirect youtubeUploadAuthorizeRedirect() {
        requireConfigured();
        GoogleCredential credential = repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE)
            .orElseThrow(GoogleNotConnectedException::new);
        String schema = TenantSchemaResolver.currentSchemaName();
        Minted minted = mint(schema, null, PURPOSE_YOUTUBE_UPLOAD);
        return new AuthorizeRedirect(authUrl(GoogleScopes.YOUTUBE_UPLOAD, minted, credential.getConnectedEmail()),
            minted.cookie());
    }

    /**
     * The {@link GoogleScopes#YOUTUBE_READONLY} incremental-consent URL the
     * callback chains into after a base connect (see that scope's javadoc for
     * why it can't ride in {@link GoogleScopes#REQUESTED}). Package-private:
     * minted only by {@code GoogleOAuthController#callback}, not exposed as
     * an endpoint - the consent is part of the connect flow itself, not an
     * action a user initiates. {@code subjectKey} carries the base connect's
     * owner forward (null for tenant-wide, the member's sub for personal) so
     * the incremental grant lands on the SAME credential row, and {@code
     * loginHint} pre-selects the just-connected Google account so the second
     * consent can't silently come back granted to a different one.
     */
    String chainedYoutubeReadonlyAuthorizeUrl(String schema, String subjectKey, String loginHint) {
        return chainedYoutubeReadonlyAuthorizeRedirect(schema, subjectKey, loginHint).url();
    }

    /**
     * {@link #chainedYoutubeReadonlyAuthorizeUrl} plus a NEW binding cookie. Only ever called by the callback
     * after it has consumed (verified) the base connect's binding, so the chained nonce inherits that trust.
     */
    AuthorizeRedirect chainedYoutubeReadonlyAuthorizeRedirect(String schema, String subjectKey, String loginHint) {
        Minted minted = mint(schema, subjectKey, PURPOSE_YOUTUBE_READONLY, "system:google-oauth-chain");
        return new AuthorizeRedirect(authUrl(GoogleScopes.YOUTUBE_READONLY, minted, loginHint), minted.cookie());
    }

    /**
     * SEC-018: verify and CONSUME the server-side binding of a decoded callback state. Must be called
     * before {@link #connect}. Single use: a second call with the same state fails.
     *
     * @param bindingCookie the {@code vd_oauth_google} cookie from the callback request, or null
     * @throws GoogleOAuthStateException when the state carries no nonce, the nonce is unknown/used/expired,
     *     the tenant/subject differ from what was bound, or the cookie does not match
     */
    public void verifyBinding(DecodedState decoded, String bindingCookie) {
        try {
            stateBinding.consume(BINDING_PROVIDER, decoded.nonce(), decoded.schema(), decoded.subjectKey(), bindingCookie);
        } catch (OAuthStateBindingException e) {
            throw new GoogleOAuthStateException(
                "This connection attempt is no longer valid - please start connecting again from the console. ("
                    + e.getMessage() + ")");
        }
    }

    /** Web mode keeps the exact calls it always made; loopback mode adds the PKCE challenge. */
    private String authUrl(String scopes, Minted minted, String loginHint) {
        if (minted.challenge() != null) {
            return client.buildAuthorizationUrl(
                clientId, redirectUri, scopes, minted.state(), loginHint, minted.challenge());
        }
        return loginHint == null
            ? client.buildAuthorizationUrl(clientId, redirectUri, scopes, minted.state())
            : client.buildAuthorizationUrl(clientId, redirectUri, scopes, minted.state(), loginHint);
    }

    private record Minted(String state, ResponseCookie cookie, String challenge) {
    }

    private Minted mint(String schema, String subjectOrNull, String purposeOrNull) {
        String initiator = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return mint(schema, subjectOrNull, purposeOrNull, initiator);
    }

    private Minted mint(String schema, String subjectOrNull, String purposeOrNull, String initiator) {
        OAuthStateBinding.Issued issued = stateBinding.issue(BINDING_PROVIDER, schema, subjectOrNull, initiator);
        return new Minted(
            stateCodec.encode(schema, subjectOrNull, purposeOrNull, issued.nonce()), issued.cookie(),
            loopbackMode ? newPkceChallenge(issued.nonce()) : null);
    }

    private String newPkceChallenge(String nonce) {
        long now = System.currentTimeMillis();
        pkce.values().removeIf(p -> p.expiresAtMillis() < now);
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        pkce.put(nonce, new PendingPkce(verifier, now + PKCE_TTL_MILLIS));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String takePkceVerifier(String nonce) {
        PendingPkce pending = nonce == null ? null : pkce.remove(nonce);
        return pending == null || pending.expiresAtMillis() < System.currentTimeMillis() ? null : pending.verifier();
    }

    /**
     * Full decode of the round-tripped state - schema plus (since V215) the
     * consenting subject. Backward-tolerant: a pre-V215 two-field payload
     * ({@code schema|epoch}) decodes as a tenant-wide connect rather than
     * throwing, so states minted just before a deploy still complete correctly
     * during their up-to-10-minute validity window.
     *
     * @throws GoogleOAuthStateException if tampered, malformed, or expired.
     */
    public DecodedState decodeConnectState(String state) {
        return stateCodec.decode(state);
    }

    /**
     * Schema-only view of {@link #decodeConnectState}, retained for callers
     * (and the pre-V215 test suite) that only ever cared which tenant the
     * state belonged to. Same validation, same exceptions.
     *
     * @return the tenant schema this state was minted for.
     * @throws GoogleOAuthStateException if tampered, malformed, or expired.
     */
    public String decodeState(String state) {
        return decodeConnectState(state).schema();
    }

    /**
     * Tenant-wide connect, kept as the one-argument shape pre-V215 callers
     * (and the test suite) use. Delegates to {@link #connect(String, String,
     * String)} as a base connect (no purpose) and returns just the email.
     */
    @Transactional
    public String connect(String code) {
        return connect(code, null, null).getConnectedEmail();
    }

    /**
     * Must be called with {@code TenantContext} already set to the schema
     * {@link #decodeConnectState} returned - see {@code
     * GoogleOAuthController#callback}.
     *
     * <p>The subject and purpose come as explicit parameters (decoded from
     * the cryptographically-authenticated state) rather than from {@code
     * TenantContext}, because the callback thread runs under the synthetic
     * {@code "system:google-oauth-callback"} subject - ambient context cannot
     * say who consented; only the state can.
     *
     * <p><b>Strictly scoped upsert</b> - the load-bearing V215 fix. The
     * pre-V215 code reconnect-or-created against "the oldest row, any subject,
     * any status", which was harmless while only one row could exist but would
     * have let the first personal connect silently overwrite the tenant-wide
     * admin credential in place, repointing every other member's agents at one
     * person's Google account. Now a tenant-wide connect can only ever touch
     * the {@code subject_key IS NULL} row and a personal connect only that
     * subject's own row; reconnect-or-create within that scope (ignoring
     * status, so a REVOKED row is reactivated rather than shadowed by a
     * competing insert - the partial unique indexes would reject the insert
     * anyway, this just makes the non-error path the correct one).
     *
     * <p><b>Incremental consents ({@code purpose != null})</b> add one scope
     * to an EXISTING connection - two extra guards apply, because the consent
     * screen is only soft-pinned to the right account via {@code login_hint}
     * and Google still lets the user switch:
     * <ul>
     *   <li>The row must already exist - an incremental consent that outlives
     *   its connection (disconnect raced the consent) must not create a new
     *   credential holding only the incremental scope.</li>
     *   <li>The Google account that completed it must be the SAME one already
     *   on the row - otherwise the saved union {@code scope} would describe
     *   grants belonging to a different account's consent, silently
     *   repointing the credential.</li>
     * </ul>
     * Either failure throws before anything is written, leaving the existing
     * base connection untouched.
     *
     * @return the saved credential - callers inspect {@code grantedScopes}
     *     (e.g. to decide whether to chain the {@code youtube.readonly}
     *     incremental consent) and {@code connectedEmail} (for {@code
     *     login_hint} on that chain).
     */
    @Transactional
    public GoogleCredential connect(String code, String subjectKey, String purpose) {
        return connect(code, subjectKey, purpose, null);
    }

    /** As above; {@code nonce} (from the decoded state) finds the PKCE verifier in loopback mode. */
    @Transactional
    public GoogleCredential connect(String code, String subjectKey, String purpose, String nonce) {
        requireConfigured();
        String verifier = takePkceVerifier(nonce);
        GoogleOAuthClient.TokenResponse tokens = verifier == null
            ? client.exchangeCode(clientId, clientSecret, redirectUri, code)
            : client.exchangeCode(clientId, clientSecret, redirectUri, code, verifier);
        if (tokens.refreshToken() == null) {
            // Happens if the user had already granted this exact client+scope
            // combination before and Google didn't re-prompt for consent -
            // access_type=offline+prompt=consent above should prevent this,
            // but if it ever does happen there's nothing usable to store.
            throw new GoogleApiException(
                "Google did not return a refresh token - if you've connected this account before, "
                    + "revoke this app's access at https://myaccount.google.com/permissions and try again."
            );
        }
        String email = client.fetchEmail(tokens.accessToken());

        Optional<GoogleCredential> existing = subjectKey == null
            ? repository.findFirstByOrderByCreatedAtAsc() // post-V215: the tenant-wide row only - see GoogleCredentialRepository
            : repository.findBySubjectKey(subjectKey);
        if (purpose != null) {
            GoogleCredential row = existing.orElseThrow(GoogleNotConnectedException::new);
            if (!row.getConnectedEmail().equalsIgnoreCase(email)) {
                throw new GoogleApiException(
                    "The incremental consent was completed with a different Google account than the connected "
                        + row.getConnectedEmail() + " - keeping the existing connection unchanged."
                );
            }
        }
        String encryptedRefreshToken = encryptionService.encrypt(tokens.refreshToken());
        GoogleCredential credential = existing
            .map(row -> {
                row.reconnect(email, encryptedRefreshToken, tokens.scope());
                return row;
            })
            .orElseGet(() -> new GoogleCredential(subjectKey, email, encryptedRefreshToken, tokens.scope()));
        repository.save(credential);
        return credential;
    }

    /**
     * The EFFECTIVE connection for the current caller - their own ACTIVE
     * personal row if they have one, else the tenant-wide ACTIVE row. Not
     * strictly "my personal row": making this per-subject-only would have
     * shown every member who never personally connected a "not connected"
     * Integrations page in a tenant that has been happily connected for
     * months, which reads as data loss (the same UX-audit rule the knowledge
     * page documents for load-failure vs empty states). The response DTO's
     * {@code connectionScope} field is how the frontend tells "mine" from
     * "the tenant's". Also the gate {@code AgentToolProvider#
     * resolveGoogleTools} uses to decide whether the Google tools exist at
     * all this turn - meaning a member with only a personal connection in an
     * otherwise unconnected tenant now correctly gets the tools, and tool
     * availability is per-viewer, which is intended.
     */
    @Transactional(readOnly = true)
    public Optional<GoogleCredential> status() {
        return effectiveActiveCredentialFor(currentSubjectOrNull());
    }

    /**
     * Tenant-wide disconnect - the original admin-gated flow. Post-V215 its
     * scope is strictly the {@code subject_key IS NULL} row: pre-V215 it
     * revoked "the oldest ACTIVE row", which once personal rows exist could
     * have been some member's personal account - an admin clicking Disconnect
     * on the tenant connection must never revoke an individual's own consent.
     * Members' personal rows are theirs to remove via {@link
     * #disconnectPersonal}.
     */
    @Transactional
    public void disconnect() {
        repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE) // post-V215: tenant-wide row only
            .ifPresent(this::revokeRemotelyThenLocally);
    }

    /**
     * Personal disconnect - removes ONLY the calling user's own row, resolved
     * from the JWT-derived {@code TenantContext} subject on the request
     * thread. A caller with no personal row is a no-op even when a tenant-wide
     * row exists: "disconnect MY account" must never widen into "disconnect
     * THE account" just because there was nothing personal to remove.
     */
    @Transactional
    public void disconnectPersonal() {
        String subject = currentSubjectOrNull();
        if (subject == null) {
            return;
        }
        repository.findBySubjectKeyAndStatus(subject, GoogleCredentialStatus.ACTIVE)
            .ifPresent(this::revokeRemotelyThenLocally);
    }

    private void revokeRemotelyThenLocally(GoogleCredential credential) {
        try {
            client.revoke(encryptionService.decrypt(credential.getEncryptedRefreshToken()));
        } catch (Exception e) {
            // Best-effort - still mark disconnected locally even if Google's
            // revoke endpoint is unreachable - logging and continuing rather than leaving a stuck "connected" state.
            log.warn("Could not revoke Google token remotely (continuing local disconnect): {}", e.getMessage());
        }
        credential.revoke();
        repository.save(credential);
    }

    /**
     * Mints an access token from the effective credential for the current
     * caller - the single credential-selection point for all five Google API
     * clients (Drive/Gmail/Calendar/Sheets/YouTube) and the workflow Drive
     * output node, all of which call this inline per request. Because
     * resolution happens HERE, inside the tool call where {@code
     * TenantContextPropagatingToolCallback} has already restored the caller's
     * context, every Google tool became per-user aware without a single change
     * in the tool layer.
     *
     * @throws GoogleNotConnectedException if neither the caller nor the tenant
     *     has an active Google connection.
     */
    @Transactional(readOnly = true)
    public String currentAccessToken() {
        GoogleCredential credential = effectiveActiveCredentialFor(currentSubjectOrNull())
            .orElseThrow(GoogleNotConnectedException::new);
        requireConfigured();
        String refreshToken = encryptionService.decrypt(credential.getEncryptedRefreshToken());
        GoogleOAuthClient.TokenResponse refreshed = client.refreshAccessToken(clientId, clientSecret, refreshToken);
        return refreshed.accessToken();
    }

    /**
     * Effective credential for the current caller, exposed so a scope check
     * ({@code GoogleCredential#hasScope}) inspects the same row {@link
     * #currentAccessToken} would mint from - checking one row and minting from
     * another would make the check worse than useless.
     */
    @Transactional(readOnly = true)
    public Optional<GoogleCredential> activeCredential() {
        return effectiveActiveCredentialFor(currentSubjectOrNull());
    }

    /**
     * THE resolution rule, public so the tool layer (or any future caller
     * that knows its subject out-of-band - e.g. a queued job resolving for its
     * requester) can apply per-user resolution explicitly instead of relying
     * on ambient {@code TenantContext}:
     *
     * <ol>
     *   <li>{@code subjectKey}'s own ACTIVE personal row, if present;</li>
     *   <li>else the tenant-wide ACTIVE row;</li>
     *   <li>else empty.</li>
     * </ol>
     *
     * <p>A null/blank subject skips straight to the tenant-wide leg - that is
     * the pre-V215 behavior verbatim, and it is what every synthetic subject
     * (webhook channels, schedulers, demo sandboxes) resolves to, since none
     * of them can ever have completed a personal consent.
     */
    @Transactional(readOnly = true)
    public Optional<GoogleCredential> effectiveActiveCredentialFor(String subjectKey) {
        if (subjectKey != null && !subjectKey.isBlank()) {
            Optional<GoogleCredential> personal = repository.findBySubjectKeyAndStatus(subjectKey, GoogleCredentialStatus.ACTIVE);
            if (personal.isPresent()) {
                return personal;
            }
        }
        return repository.findFirstByStatusOrderByCreatedAtAsc(GoogleCredentialStatus.ACTIVE); // tenant-wide fallback
    }

    private String currentSubjectOrNull() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }
}
