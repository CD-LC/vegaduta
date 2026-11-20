package com.agenticai.core.admin.sso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mint, list, revoke and authenticate SCIM provisioning bearer tokens. Fronts {@link
 * TenantScimTokenRepository} for {@link TenantSsoController} and {@link
 * ScimProvisioningController}; only the SHA-256 hash of a token is ever stored.
 */
@Service
public class TenantScimTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record ScimTokenView(UUID id, String label, Instant createdAt, Instant lastUsedAt) {}

    /** The plaintext token is only available here, at mint time. */
    public record MintedToken(UUID id, String token, String label) {}

    private final TenantScimTokenRepository tokenRepository;

    public TenantScimTokenService(TenantScimTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional(readOnly = true)
    public List<ScimTokenView> list(String tenantSchema) {
        return tokenRepository.findByTenantSchemaAndRevokedAtIsNull(tenantSchema).stream()
                .map(
                        t ->
                                new ScimTokenView(
                                        t.getId(),
                                        t.getLabel(),
                                        t.getCreatedAt(),
                                        t.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public MintedToken mint(String tenantSchema, String label, String createdBy) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "vdscim_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        TenantScimToken row = new TenantScimToken();
        row.setTenantSchema(tenantSchema);
        row.setTokenHash(sha256Hex(token));
        row.setLabel(label == null || label.isBlank() ? "SCIM token" : label.trim());
        row.setCreatedBy(createdBy);
        tokenRepository.save(row);
        return new MintedToken(row.getId(), token, row.getLabel());
    }

    /** Revokes the token only when it belongs to {@code tenantSchema} (no cross-tenant revoke). */
    @Transactional
    public void revoke(UUID id, String tenantSchema) {
        tokenRepository
                .findById(id)
                .filter(t -> t.getTenantSchema().equals(tenantSchema))
                .ifPresent(
                        t -> {
                            t.setRevokedAt(Instant.now());
                            tokenRepository.save(t);
                        });
    }

    /**
     * The tenant schema a live token belongs to, touching its last-used time; empty when the token
     * is unknown or revoked.
     */
    @Transactional
    public Optional<String> authenticate(String token) {
        Optional<TenantScimToken> row =
                tokenRepository.findByTokenHashAndRevokedAtIsNull(sha256Hex(token));
        row.ifPresent(t -> tokenRepository.markUsed(t.getId()));
        return row.map(TenantScimToken::getTenantSchema);
    }
}
