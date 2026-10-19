package com.agenticai.core.agents.trust;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.agents.a2a.A2aCardSigningService;
import com.agenticai.core.agents.a2a.A2aProperties;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Mints, introspects and verifies <b>delegation tokens</b>: Ed25519 JWTs in
 * which an agent acts on behalf of a named user toward a named audience -
 * RFC 8693 token-exchange semantics ({@code sub}=agent, {@code act}=delegator)
 * plus audience binding ({@code aud}) so a token minted for one service cannot
 * be replayed against another.
 *
 * <p>Signed by the deployment's A2A operator key ({@link A2aCardSigningService})
 * - the same {@code kid} resolves at {@code /.well-known/jwks.json}, so a
 * counterparty that already trusts our agent cards can verify delegation
 * tokens with zero new key distribution.
 *
 * <p><b>Revocation model:</b> the {@link DelegationGrant} is the revocable
 * unit; each minted token is additionally recorded as a {@link
 * DelegationTokenRecord} keyed by {@code jti}. Stateless verification
 * (signature + claims only) is offered by {@link #verifySignatureAndClaims}
 * for offline/edge use; {@link #verify} and {@link #introspect} also consult
 * the recorded state and are the honest answer to "is this token still good".
 *
 * <p><b>Scope contract:</b> scopes are space-separated OAuth-style strings.
 * {@code pay:*} scopes require the grant to bind a {@code wallet_id} - a
 * payment delegation cannot later be aimed at a different wallet.
 */
@Service
public class DelegationTokenService {

    private static final Logger log = LoggerFactory.getLogger(DelegationTokenService.class);

    /** JWT {@code token_use} marker so verifiers can policy on token kind. */
    public static final String TOKEN_USE = "delegation";

    static final String SCOPE_PAY_PREFIX = "pay:";
    static final long DEFAULT_TTL_SECONDS = 3600;

    private final DelegationGrantRepository grantRepository;
    private final DelegationTokenRepository tokenRepository;
    private final ObjectProvider<A2aCardSigningService> signingService;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final ObjectMapper objectMapper = JsonMappers.plain();
    private final Clock clock;
    private final String issuer;
    private final long maxTtlSeconds;

    @Autowired
    public DelegationTokenService(
        DelegationGrantRepository grantRepository,
        DelegationTokenRepository tokenRepository,
        ObjectProvider<A2aCardSigningService> signingService,
        ObjectProvider<AuditLogService> auditLogService,
        A2aProperties a2aProperties,
        @Value("${app.delegation.issuer:}") String issuer,
        @Value("${app.delegation.max-ttl-seconds:3600}") long maxTtlSeconds
    ) {
        this(grantRepository, tokenRepository, signingService, auditLogService,
            StringUtils.hasText(issuer) ? issuer : a2aProperties.getPublicBaseUrl(),
            maxTtlSeconds, Clock.systemUTC());
    }

    /** Test seam - fixed clock. */
    DelegationTokenService(
        DelegationGrantRepository grantRepository,
        DelegationTokenRepository tokenRepository,
        ObjectProvider<A2aCardSigningService> signingService,
        ObjectProvider<AuditLogService> auditLogService,
        String issuer, long maxTtlSeconds, Clock clock
    ) {
        this.grantRepository = grantRepository;
        this.tokenRepository = tokenRepository;
        this.signingService = signingService;
        this.auditLogService = auditLogService;
        this.issuer = issuer;
        this.maxTtlSeconds = maxTtlSeconds > 0 ? maxTtlSeconds : DEFAULT_TTL_SECONDS;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- grants

    /**
     * Record a delegation grant. {@code grantedBy} is the delegating user's
     * subject - the controller resolves it from {@link TenantContext}, never
     * from the request body.
     */
    @Transactional
    public DelegationGrant createGrant(
        UUID agentId, String grantedBy, String audience, List<String> scopes,
        long requestedMaxTtlSeconds, Instant expiresAt, UUID walletId, Long spendCapMinor
    ) {
        if (!StringUtils.hasText(audience)) {
            throw new DelegationException("AUDIENCE_REQUIRED",
                "audience is required - an unscoped delegation token can be replayed anywhere");
        }
        if (scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(s -> !StringUtils.hasText(s))) {
            throw new DelegationException("SCOPES_REQUIRED", "at least one non-blank scope is required");
        }
        boolean pays = scopes.stream().anyMatch(s -> s.startsWith(SCOPE_PAY_PREFIX));
        if (pays && walletId == null) {
            throw new DelegationException("WALLET_REQUIRED",
                "a pay:* scope must bind a wallet_id at grant time");
        }
        if (spendCapMinor != null && spendCapMinor <= 0) {
            throw new DelegationException("SPEND_CAP_INVALID", "spendCapMinor must be positive");
        }
        if (spendCapMinor != null && !pays) {
            throw new DelegationException("SPEND_CAP_NEEDS_PAY",
                "spendCapMinor only has meaning with a pay:* scope");
        }
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw new DelegationException("EXPIRES_IN_PAST", "expiresAt must be in the future");
        }
        long cappedTtl = requestedMaxTtlSeconds > 0
            ? Math.min(requestedMaxTtlSeconds, maxTtlSeconds)
            : maxTtlSeconds;
        if (cappedTtl <= 0) {
            throw new DelegationException("TTL_INVALID", "maxTokenTtlSeconds must be positive");
        }

        DelegationGrant grant = new DelegationGrant();
        grant.setId(UUID.randomUUID());
        grant.setAgentId(agentId);
        grant.setGrantedBy(grantedBy);
        grant.setAudience(audience.trim());
        grant.setScopesJson(writeJson(scopes.stream().map(String::trim).toList()));
        grant.setMaxTokenTtlSeconds(cappedTtl);
        grant.setExpiresAt(expiresAt);
        grant.setWalletId(walletId);
        grant.setSpendCapMinor(spendCapMinor);
        DelegationGrant saved = grantRepository.save(grant);
        audit("delegation.grant.created", saved.getId().toString(), AuditOutcome.APPROVED,
            "agent=" + agentId + " aud=" + audience + " scopes=" + scopes);
        return saved;
    }

    /**
     * Mint a token under an ACTIVE grant. The token's {@code exp} is the
     * min of (requested ttl, the grant's ttl cap, the grant's own expiry) -
     * a token can never outlive its grant.
     */
    @Transactional
    public MintedToken mintToken(UUID grantId, Long requestedTtlSeconds) {
        DelegationGrant grant = grantRepository.findById(grantId)
            .orElseThrow(() -> new DelegationException("GRANT_NOT_FOUND", "no such grant"));
        assertGrantUsable(grant, clock.instant());

        A2aCardSigningService signer = signingService.getIfAvailable();
        if (signer == null || !signer.isEnabled()) {
            throw new DelegationException("SIGNING_DISABLED",
                "delegation tokens need a2a signing enabled (app.a2a.signing.*)");
        }

        Instant now = clock.instant();
        long ttl = requestedTtlSeconds != null && requestedTtlSeconds > 0
            ? Math.min(requestedTtlSeconds, grant.getMaxTokenTtlSeconds())
            : grant.getMaxTokenTtlSeconds();
        Instant exp = now.plusSeconds(ttl);
        if (exp.isAfter(grant.getExpiresAt())) {
            exp = grant.getExpiresAt();
        }
        String jti = UUID.randomUUID().toString();
        List<String> scopes = readScopes(grant.getScopesJson());
        boolean pays = scopes.stream().anyMatch(s -> s.startsWith(SCOPE_PAY_PREFIX));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        if (StringUtils.hasText(issuer)) {
            claims.put("iss", issuer);
        }
        claims.put("sub", "agent:" + grant.getAgentId());
        // RFC 8693 actor claim: who the agent acts for.
        claims.put("act", Map.of("sub", "user:" + grant.getGrantedBy()));
        claims.put("tenant_id", currentTenant());
        claims.put("agent_id", grant.getAgentId().toString());
        claims.put("aud", grant.getAudience());
        claims.put("scope", String.join(" ", scopes));
        claims.put("grant_id", grant.getId().toString());
        if (pays && grant.getWalletId() != null) {
            claims.put("wallet_id", grant.getWalletId().toString());
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", exp.getEpochSecond());
        claims.put("jti", jti);
        claims.put("token_use", TOKEN_USE);
        claims.put("agent_platform", "vegaduta");

        String token = signer.signCompactJws(header, claims);
        if (token == null) {
            throw new DelegationException("SIGNING_FAILED", "signing service returned no token");
        }

        DelegationTokenRecord record = new DelegationTokenRecord();
        record.setJti(jti);
        record.setGrantId(grant.getId());
        record.setAgentId(grant.getAgentId());
        record.setAudience(grant.getAudience());
        record.setIssuedAt(now);
        record.setExpiresAt(exp);
        record.setWalletId(pays ? grant.getWalletId() : null);
        tokenRepository.save(record);
        audit("delegation.token.minted", jti, AuditOutcome.APPROVED,
            "grant=" + grantId + " exp=" + exp);
        return new MintedToken(token, jti, exp);
    }



    // ------------------------------------------------------------ verification

    /**
     * Full verification for a programmatic caller: signature, claims, expiry,
     * audience, required scope, AND recorded revocation state. Fails closed:
     * any problem throws {@link DelegationException} with a stable code.
     *
     * <p><b>Tenant note:</b> the recorded checks query tenant-scoped tables in
     * the CURRENT TenantContext - callers on a public/tenant-less path must
     * either run inside the token's tenant (see {@link #verifyStateless} +
     * {@link #verifyRecorded}, which exist exactly for that hop) or accept
     * that only same-tenant introspection works here.
     */
    public VerifiedDelegation verify(String token, String requiredAudience, String requiredScopePrefix) {
        VerifiedDelegation v = verifyStateless(token, requiredAudience, requiredScopePrefix);
        verifyRecorded(v);
        return v;
    }

    /**
     * Signature + claims + expiry + audience + scope - NO database access.
     * Safe to call with any (or no) TenantContext; the recorded-state half is
     * {@link #verifyRecorded}, which the caller runs inside the token's own
     * tenant schema ({@code v.tenantId()}).
     */
    public VerifiedDelegation verifyStateless(String token, String requiredAudience, String requiredScopePrefix) {
        VerifiedDelegation v = verifySignatureAndClaims(token);
        Instant now = clock.instant();
        if (v.expiresAt().isBefore(now)) {
            throw new DelegationException("TOKEN_EXPIRED", "token expired");
        }
        if (requiredAudience != null && !requiredAudience.equals(v.audience())) {
            throw new DelegationException("AUDIENCE_MISMATCH",
                "token aud does not match this resource");
        }
        if (requiredScopePrefix != null && v.scopes().stream().noneMatch(s -> s.startsWith(requiredScopePrefix))) {
            throw new DelegationException("SCOPE_MISSING", "token lacks scope " + requiredScopePrefix);
        }
        return v;
    }

    /**
     * The recorded-state half of {@link #verify}: the {@code jti} row must be
     * ACTIVE and its grant ACTIVE + unexpired. MUST run inside the token's
     * tenant schema (it queries tenant tables). Returns the grant so callers
     * that enforce spend ({@link #recordSpend}) see the same row.
     */
    @Transactional
    public DelegationGrant verifyRecorded(VerifiedDelegation v) {
        Instant now = clock.instant();
        DelegationTokenRecord record = tokenRepository.findById(v.jti()).orElse(null);
        if (record == null || record.getStatus() != DelegationStatus.ACTIVE) {
            throw new DelegationException("TOKEN_REVOKED", "token is revoked or unknown");
        }
        DelegationGrant grant = grantRepository.findById(record.getGrantId()).orElse(null);
        if (grant == null || grant.getStatus() != DelegationStatus.ACTIVE
            || !grant.getExpiresAt().isAfter(now)) {
            throw new DelegationException("GRANT_INACTIVE", "the backing grant is revoked or expired");
        }
        return grant;
    }

    /**
     * Recorded checks + cumulative spend, atomically under the grant's
     * {@code SELECT ... FOR UPDATE} row lock (V406). MUST run inside the
     * payer's tenant schema. Throws {@code SPEND_CAP_EXCEEDED} when
     * {@code spent + amount} would cross {@code spend_cap_minor}; a NULL cap
     * means only the wallet balance bounds spend.
     */
    @Transactional
    public DelegationGrant recordSpend(String jti, long amountMinor) {
        if (amountMinor <= 0) {
            throw new DelegationException("AMOUNT_INVALID", "spend amount must be positive");
        }
        Instant now = clock.instant();
        DelegationTokenRecord record = tokenRepository.findById(jti).orElse(null);
        if (record == null || record.getStatus() != DelegationStatus.ACTIVE) {
            throw new DelegationException("TOKEN_REVOKED", "token is revoked or unknown");
        }
        DelegationGrant grant = grantRepository.findByIdForUpdate(record.getGrantId()).orElse(null);
        if (grant == null || grant.getStatus() != DelegationStatus.ACTIVE
            || !grant.getExpiresAt().isAfter(now)) {
            throw new DelegationException("GRANT_INACTIVE", "the backing grant is revoked or expired");
        }
        if (grant.getSpendCapMinor() != null
            && Math.addExact(grant.getSpentMinor(), amountMinor) > grant.getSpendCapMinor()) {
            throw new DelegationException("SPEND_CAP_EXCEEDED",
                "grant spend " + grant.getSpentMinor() + " + " + amountMinor
                    + " exceeds cap " + grant.getSpendCapMinor());
        }
        grant.applySpend(amountMinor, now);
        audit("delegation.spend.recorded", jti, AuditOutcome.APPROVED,
            "grant=" + grant.getId() + " amount=" + amountMinor
                + " spent=" + grant.getSpentMinor());
        return grant;
    }

    /**
     * Compensating release for {@link #recordSpend} when the settlement leg it
     * guarded is reversed (x402 credit leg failed / refund). Runs under the
     * same row lock; clamps at zero rather than throwing - the release must
     * never fail and leave the cap permanently consumed.
     */
    @Transactional
    public void releaseSpend(String jti, long amountMinor) {
        DelegationTokenRecord record = tokenRepository.findById(jti).orElse(null);
        if (record == null) {
            return;
        }
        DelegationGrant grant = grantRepository.findByIdForUpdate(record.getGrantId()).orElse(null);
        if (grant != null) {
            grant.releaseSpend(amountMinor, clock.instant());
        }
    }

    /**
     * Signature + claims only - no DB state. For offline/edge verification
     * where the recorded-revocation table is unreachable; callers that can
     * reach the DB should prefer {@link #verify}.
     */
    public VerifiedDelegation verifySignatureAndClaims(String token) {
        A2aCardSigningService signer = signingService.getIfAvailable();
        if (signer == null || !signer.isEnabled()) {
            throw new DelegationException("SIGNING_DISABLED", "verification key unavailable");
        }
        Map<String, Object> claims = verifyCompactJws(token, signer);
        if (!TOKEN_USE.equals(claims.get("token_use"))) {
            throw new DelegationException("WRONG_TOKEN_USE", "not a delegation token");
        }
        return new VerifiedDelegation(
            str(claims.get("jti")), str(claims.get("grant_id")),
            uuid(str(claims.get("agent_id"))), str(claims.get("tenant_id")),
            delegator(claims.get("act")), str(claims.get("aud")),
            splitScopes(str(claims.get("scope"))),
            uuidOrNull(str(claims.get("wallet_id"))),
            Instant.ofEpochSecond(num(claims.get("exp"))));
    }

    /** RFC 7662-style introspection - active flag + claims or a reason code. */
    public Map<String, Object> introspect(String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            VerifiedDelegation v = verify(token, null, null);
            out.put("active", true);
            out.put("sub", "agent:" + v.agentId());
            out.put("agent_id", v.agentId().toString());
            out.put("act", Map.of("sub", "user:" + v.delegator()));
            out.put("tenant_id", v.tenantId());
            out.put("aud", v.audience());
            out.put("scope", String.join(" ", v.scopes()));
            out.put("exp", v.expiresAt().getEpochSecond());
            out.put("jti", v.jti());
            out.put("grant_id", v.grantId());
            out.put("token_use", TOKEN_USE);
        } catch (DelegationException e) {
            out.put("active", false);
            out.put("reason", e.getCode());
        }
        return out;
    }

    // -------------------------------------------------------------- revocation

    @Transactional
    public DelegationGrant revokeGrant(UUID grantId, String reason) {
        DelegationGrant grant = grantRepository.findById(grantId).orElse(null);
        if (grant == null) {
            return null;
        }
        String actor = currentSubject();
        grant.markRevoked(actor, reason, clock.instant());
        int tokens = tokenRepository.revokeAllForGrant(grantId);
        audit("delegation.grant.revoked", grantId.toString(), AuditOutcome.APPROVED,
            "tokens_revoked=" + tokens + " reason=" + reason);
        return grant;
    }

    @Transactional
    public boolean revokeToken(String jti) {
        return tokenRepository.findById(jti).map(record -> {
            record.setStatus(DelegationStatus.REVOKED);
            audit("delegation.token.revoked", jti, AuditOutcome.APPROVED, null);
            return true;
        }).orElse(false);
    }

    public List<DelegationGrant> listGrants(UUID agentId) {
        return grantRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public Optional<DelegationGrant> findGrant(UUID grantId) {
        return grantRepository.findById(grantId);
    }

    // ---------------------------------------------------------------- internals

    private void assertGrantUsable(DelegationGrant grant, Instant now) {
        if (grant.getStatus() == DelegationStatus.REVOKED) {
            throw new DelegationException("GRANT_REVOKED", "grant is revoked");
        }
        if (!grant.getExpiresAt().isAfter(now)) {
            throw new DelegationException("GRANT_EXPIRED", "grant expired");
        }
    }

    /** Ed25519 verify of a compact JWS against the operator public key + parse claims. */
    private Map<String, Object> verifyCompactJws(String token, A2aCardSigningService signer) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new DelegationException("MALFORMED", "not a compact JWS");
            }
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(signer.publicKey());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new DelegationException("BAD_SIGNATURE", "signature does not verify");
            }
            Map<String, Object> header = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[0]), Map.class);
            if (!"EdDSA".equals(header.get("alg"))) {
                throw new DelegationException("WRONG_ALG", "unexpected alg");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]), Map.class);
            return claims;
        } catch (DelegationException e) {
            throw e;
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            throw new DelegationException("MALFORMED", "token did not parse or verify: " + e.getMessage());
        }
    }

    private String currentTenant() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("unknown");
    }

    private String currentSubject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private void audit(String action, String targetId, AuditOutcome outcome, String detail) {
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                audit.record(currentSubject(), action, "agent_delegation", targetId, outcome, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {}: {}", action, e.toString());
        }
    }

    private String writeJson(List<String> scopes) {
        try {
            return objectMapper.writeValueAsString(scopes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readScopes(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> splitScopes(String scope) {
        return StringUtils.hasText(scope) ? List.of(scope.trim().split("\\s+")) : List.of();
    }

    private static String delegator(Object act) {
        if (act instanceof Map<?, ?> m && m.get("sub") instanceof String s && s.startsWith("user:")) {
            return s.substring(5);
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(o));
    }

    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }

    private static UUID uuidOrNull(String s) {
        return StringUtils.hasText(s) ? UUID.fromString(s) : null;
    }

    /** Result of {@link #mintToken}: the compact JWT plus its registry fields. */
    public record MintedToken(String token, String jti, Instant expiresAt) {
    }

    /** Verified token contents - the claims a relying party acts on. */
    public record VerifiedDelegation(
        String jti, String grantId, UUID agentId, String tenantId,
        String delegator, String audience, List<String> scopes,
        UUID walletId, Instant expiresAt) {
    }
}
