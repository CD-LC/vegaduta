package com.agenticai.core.mcp.atlassian;

import com.agenticai.core.crypto.EncryptionService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the platform's one-time Dynamic Client Registration with Atlassian -
 * see {@link AtlassianPlatformOAuthClient}'s javadoc. {@link #ensureRegistered}
 * is idempotent and safe to call on every {@link AtlassianOAuthService#authorizeUrl()}
 * build: a cheap single-row read in the steady state, a real DCR call only
 * the very first time any tenant ever clicks "Connect via Atlassian" on this
 * deployment.
 */
@Service
public class AtlassianPlatformClientService {

    private static final Logger log = LoggerFactory.getLogger(AtlassianPlatformClientService.class);

    private final AtlassianPlatformOAuthClientRepository repository;
    private final AtlassianOAuthClient client;
    private final EncryptionService encryptionService;

    public AtlassianPlatformClientService(
        AtlassianPlatformOAuthClientRepository repository,
        AtlassianOAuthClient client,
        EncryptionService encryptionService
    ) {
        this.repository = repository;
        this.client = client;
        this.encryptionService = encryptionService;
    }

    /**
     * @param redirectUri this deployment's current callback URL
     *     ({@code app.atlassian.oauth.redirect-uri}) - registered with
     *     Atlassian on first use. If a previously-registered client's
     *     stored {@code redirectUri} no longer matches (e.g. a domain/
     *     environment change), the STALE client is still returned rather
     *     than silently blocking every tenant - a live mismatch fails
     *     loudly and diagnosably at Atlassian's own OAuth error response
     *     (redirect_uri mismatch is a protocol violation Atlassian itself
     *     rejects), which is enough visibility for v1. Building
     *     auto-re-registration tooling is an explicit follow-up.
     */
    @Transactional
    public ResolvedClient ensureRegistered(String redirectUri) {
        Optional<AtlassianPlatformOAuthClient> existing = repository.findFirstByOrderByRegisteredAtAsc();
        if (existing.isPresent()) {
            AtlassianPlatformOAuthClient row = existing.get();
            if (!redirectUri.equals(row.getRedirectUri())) {
                log.warn(
                    "Atlassian OAuth client was registered for redirect_uri '{}' but this deployment is now "
                        + "configured for '{}' - future Atlassian connect attempts will fail at Atlassian's own "
                        + "OAuth error response until the atlassian_oauth_client row is deleted to force "
                        + "re-registration.",
                    row.getRedirectUri(), redirectUri);
            }
            String clientSecret = row.getEncryptedClientSecret() == null ? null : encryptionService.decrypt(row.getEncryptedClientSecret());
            return new ResolvedClient(row.getClientId(), clientSecret, row.getTokenEndpointAuthMethod());
        }

        AtlassianOAuthClient.DynamicClientRegistration registration = client.registerClient(redirectUri);
        String encryptedSecret = registration.clientSecret() == null ? null : encryptionService.encrypt(registration.clientSecret());
        repository.save(new AtlassianPlatformOAuthClient(
            registration.clientId(), encryptedSecret, registration.tokenEndpointAuthMethod(), redirectUri));
        log.info("Registered a new platform-wide Atlassian OAuth client (auth method: {})", registration.tokenEndpointAuthMethod());
        return new ResolvedClient(registration.clientId(), registration.clientSecret(), registration.tokenEndpointAuthMethod());
    }

    /** In-memory only, decrypted - never persisted or returned over HTTP as-is. */
    public record ResolvedClient(String clientId, String clientSecret, String tokenEndpointAuthMethod) {
    }
}
