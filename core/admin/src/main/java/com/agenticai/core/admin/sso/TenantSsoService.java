package com.agenticai.core.admin.sso;

import com.agenticai.core.admin.identity.IdentityProviderAdminPort;
import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.net.ReservedAddressRanges;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant BYO-IdP: stores a tenant's own identity provider config ({@link TenantSsoConfig}) and
 * mirrors it into the shared Keycloak realm as an IdP instance aliased {@code tenant-<schema>}.
 *
 * <p><b>Why a fixed alias, not a tenant-chosen one:</b> the shared realm's IdP namespace is global
 * - a tenant naming their IdP {@code okta} would collide with (or squat) every other tenant's.
 * {@code tenant-<schema>} is unique by construction and unforgeable across tenants.
 *
 * <p><b>What Keycloak does vs what we do:</b> Keycloak brokers the actual login (OIDC
 * authorization-code or SAML POST) and lands the user with a realm account carrying the {@code
 * tenant_id} attribute via an IdP attribute-mapper the tenant's IdP sends. This service only owns
 * the IdP instance's lifecycle - create/update/delete - plus the read-model the admin console
 * renders.
 *
 * <p><b>Secret handling:</b> the OIDC client secret is stored AES-GCM ({@link EncryptionService})
 * and pushed to Keycloak inside the IdP instance's {@code config.clientSecret}. It is never
 * re-served - reads expose only {@code clientSecretSet}.
 *
 * <p><b>Shared-realm hardening (SEC-019).</b> Every tenant IdP lives in the ONE shared realm, so it
 * must not be able to act on anyone but its own tenant: {@code trustEmail=false} (an IdP-asserted
 * email is never treated as verified), {@code hideOnLogin=true} (never rendered on the shared login
 * page - users reach it via {@code kc_idp_hint=tenant-<id>} on the authorize URL), the
 * first-broker- login flow is pinned to {@code app.sso.tenant-idp.first-broker-login-flow}
 * (re-applied on every upsert), a {@code hardcoded-attribute-idp-mapper} forces {@code
 * tenant_id=<owning tenant>} on every login (sync mode FORCE, so a tenant IdP cannot assert another
 * tenant's id), and every URL Keycloak fetches server-side must be https and resolve only to public
 * addresses.
 */
// Per-tenant SSO connection management is Keycloak-only today: the Auth0 adapter implements
// IdentityUserAdminPort only (see Auth0UserAdminClient's class javadoc), so under
// app.identity.provider=auth0 there is no IdentityProviderAdminPort bean to inject. Gated rather
// than made optional so /api/tenant/sso 404s instead of starting half-wired.
@Service
@Profile("security")
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "keycloak", matchIfMissing = true)
public class TenantSsoService {

    private final TenantSsoConfigRepository repository;
    private final IdentityProviderAdminPort keycloak;
    private final EncryptionService encryptionService;
    private final String issuerUri;
    private final String additionalIssuers;
    private final String firstBrokerLoginFlow;
    private final HostResolver hostResolver;

    /** Name of the hardcoded tenant_id mapper this service owns on every tenant IdP (SEC-019). */
    static final String TENANT_ID_MAPPER_NAME = "vegaduta-tenant-id";

    /** DNS seam so the private-address check is unit-testable offline. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public TenantSsoService(
            TenantSsoConfigRepository repository,
            IdentityProviderAdminPort keycloak,
            EncryptionService encryptionService,
            String issuerUri,
            String additionalIssuers) {
        this(
                repository,
                keycloak,
                encryptionService,
                issuerUri,
                additionalIssuers,
                "first broker login",
                InetAddress::getAllByName);
    }

    @Autowired
    public TenantSsoService(
            TenantSsoConfigRepository repository,
            IdentityProviderAdminPort keycloak,
            EncryptionService encryptionService,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${app.keycloak.additional-issuers:}") String additionalIssuers,
            @Value("${app.sso.tenant-idp.first-broker-login-flow:first broker login}")
                    String firstBrokerLoginFlow) {
        this(
                repository,
                keycloak,
                encryptionService,
                issuerUri,
                additionalIssuers,
                firstBrokerLoginFlow,
                InetAddress::getAllByName);
    }

    TenantSsoService(
            TenantSsoConfigRepository repository,
            IdentityProviderAdminPort keycloak,
            EncryptionService encryptionService,
            String issuerUri,
            String additionalIssuers,
            String firstBrokerLoginFlow,
            HostResolver hostResolver) {
        this.repository = repository;
        this.keycloak = keycloak;
        this.encryptionService = encryptionService;
        this.issuerUri = issuerUri;
        this.additionalIssuers = additionalIssuers == null ? "" : additionalIssuers;
        this.firstBrokerLoginFlow =
                firstBrokerLoginFlow == null || firstBrokerLoginFlow.isBlank()
                        ? "first broker login"
                        : firstBrokerLoginFlow.trim();
        this.hostResolver = hostResolver;
    }

    /** Alias rule - unique, unforgeable, self-describing in the Keycloak admin console. */
    public static String aliasFor(String tenantSchema) {
        return "tenant-" + tenantSchema;
    }

    public Optional<TenantSsoConfig> findConfig(String tenantSchema) {
        return repository.findByTenantSchema(tenantSchema);
    }

    /**
     * The URL the tenant pastes into their IdP's "redirect/callback URI" field. Broker endpoints
     * live on the realm's PUBLIC issuer host - we prefer {@code additional-issuers}' first entry
     * (the public auth host) and fall back to the primary issuer, rather than the admin API's base
     * URL, which is internal-only on deployments where it differs.
     */
    public String brokerRedirectUri(String tenantSchema) {
        String base = issuerUri;
        for (String candidate : additionalIssuers.split(",")) {
            if (!candidate.isBlank()) {
                base = candidate.trim();
                break;
            }
        }
        return base + "/broker/" + aliasFor(tenantSchema) + "/endpoint";
    }

    @Transactional
    public TenantSsoConfig upsert(String tenantSchema, TenantSsoUpsertRequest req, String subject) {
        validate(tenantSchema, req);

        TenantSsoConfig config =
                repository.findByTenantSchema(tenantSchema).orElseGet(TenantSsoConfig::new);
        config.setTenantSchema(tenantSchema);
        config.setAlias(aliasFor(tenantSchema));
        config.setProviderType(req.providerType().name());
        config.setDisplayName(req.displayName().trim());
        config.setEnabled(req.enabled() == null || req.enabled());

        if (req.providerType() == TenantSsoConfig.ProviderType.OIDC) {
            config.setOidcDiscoveryUrl(blank(req.oidcDiscoveryUrl()));
            config.setOidcAuthorizationUrl(blank(req.oidcAuthorizationUrl()));
            config.setOidcTokenUrl(blank(req.oidcTokenUrl()));
            config.setOidcUserInfoUrl(blank(req.oidcUserInfoUrl()));
            config.setOidcJwksUrl(blank(req.oidcJwksUrl()));
            config.setOidcIssuer(blank(req.oidcIssuer()));
            config.setOidcClientId(blank(req.oidcClientId()));
            // Blank secret on update = keep the stored one; a tenant
            // rotating their IdP's endpoints shouldn't have to re-paste it.
            if (req.clientSecret() != null && !req.clientSecret().isBlank()) {
                config.setClientSecretEncrypted(encryptionService.encrypt(req.clientSecret()));
            } else if (config.getClientSecretEncrypted() == null) {
                throw new IllegalArgumentException(
                        "clientSecret is required for OIDC - a confidential IdP client can't"
                                + " authenticate without it");
            }
        } else {
            config.setSamlEntityId(blank(req.samlEntityId()));
            config.setSamlSsoUrl(blank(req.samlSsoUrl()));
            config.setSamlX509Cert(blank(req.samlX509Cert()));
            config.setSamlNameIdFormat(
                    req.samlNameIdFormat() == null || req.samlNameIdFormat().isBlank()
                            ? "urn:oasis:names:tc:SAML:2.0:nameid-format:persistent"
                            : req.samlNameIdFormat().trim());
        }
        if (config.getCreatedBy() == null) {
            config.setCreatedBy(subject);
        }
        config.setUpdatedAt(Instant.now());

        pushToKeycloak(config, req.clientSecret());
        config.setLastSyncedAt(Instant.now());
        return repository.save(config);
    }

    @Transactional
    public void delete(String tenantSchema) {
        repository
                .findByTenantSchema(tenantSchema)
                .ifPresent(
                        config -> {
                            // Keycloak-side removal first: if it fails we keep our row so
                            // the retry has the config to work with.
                            if (keycloak.getIdentityProvider(config.getAlias()).isPresent()) {
                                keycloak.deleteIdentityProvider(config.getAlias());
                            }
                            repository.delete(config);
                        });
    }

    /**
     * Read-side projection for the admin console: stored config + whether the Keycloak-side
     * instance actually exists/enabled right now, so the UI can show "configured but disabled in
     * realm" drift instead of trusting our row blindly.
     */
    public TenantSsoStatus status(String tenantSchema) {
        Optional<TenantSsoConfig> config = repository.findByTenantSchema(tenantSchema);
        if (config.isEmpty()) {
            return new TenantSsoStatus(
                    false, null, null, null, null, false, brokerRedirectUri(tenantSchema));
        }
        TenantSsoConfig c = config.get();
        Boolean realmEnabled =
                keycloak.getIdentityProvider(c.getAlias())
                        .map(rep -> Boolean.TRUE.equals(rep.get("enabled")))
                        .orElse(null); // null = "not pushed to realm" - distinct from disabled
        return new TenantSsoStatus(
                true,
                TenantSsoConfig.ProviderType.valueOf(c.getProviderType()),
                c.getDisplayName(),
                c.getAlias(),
                realmEnabled,
                c.getClientSecretEncrypted() != null,
                brokerRedirectUri(tenantSchema));
    }

    // --------------------------- Keycloak push -----------------------------

    private void pushToKeycloak(TenantSsoConfig config, String plainSecret) {
        Map<String, Object> rep =
                keycloak.getIdentityProvider(config.getAlias())
                        .map(HashMap::new)
                        .orElseGet(HashMap::new);
        rep.put("alias", config.getAlias());
        rep.put("displayName", config.getDisplayName());
        rep.put("enabled", config.isEnabled());
        rep.put("providerId", config.getProviderType().equals("OIDC") ? "oidc" : "saml");
        // SEC-019: a tenant's IdP can assert ANY email; treating it as verified
        // would let it claim (and link to) accounts of other tenants' users.
        rep.put("trustEmail", false);
        // SEC-019: never shown on the shared login page (phishing: a tenant
        // could otherwise add "Sign in with Google" pointing at its own IdP).
        // Keycloak >= 26 reads the top-level field, older versions the config key.
        rep.put("hideOnLogin", true);
        // First-broker-login is the auto-provision flow: a user arriving via
        // this IdP gets a realm account created on first login. SEC-019: the
        // flow is pinned (put, not putIfAbsent, so drift is repaired) to the
        // configured alias; with trustEmail=false Keycloak's built-in flow
        // requires email/re-auth verification before linking an existing
        // account, and a dedicated no-link flow can be configured.
        rep.put("firstBrokerLoginFlowAlias", firstBrokerLoginFlow);
        rep.remove("postBrokerLoginFlowAlias");
        rep.putIfAbsent("updateProfileFirstLoginMode", "on");

        @SuppressWarnings("unchecked")
        Map<String, Object> kcConfig =
                rep.get("config") instanceof Map<?, ?> m
                        ? new HashMap<>((Map<String, Object>) m)
                        : new HashMap<>();
        if (config.getProviderType().equals("OIDC")) {
            fillOidcConfig(config, kcConfig, plainSecret);
        } else {
            fillSamlConfig(config, kcConfig);
        }
        kcConfig.put("hideOnLoginPage", "true");
        kcConfig.put("trustEmail", "false");
        rep.put("config", kcConfig);

        if (keycloak.getIdentityProvider(config.getAlias()).isPresent()) {
            keycloak.updateIdentityProvider(config.getAlias(), rep);
        } else {
            keycloak.createIdentityProvider(rep);
        }
        ensureTenantIdMapper(config.getAlias(), config.getTenantSchema());
    }

    /**
     * SEC-019: every login through a tenant IdP gets {@code tenant_id} forced to the OWNING tenant
     * (sync mode FORCE re-applies it on every login), so the IdP cannot place a user into another
     * tenant by asserting a different tenant_id. Idempotent: the mapper this service owns is
     * updated in place.
     */
    void ensureTenantIdMapper(String alias, String tenantId) {
        Map<String, Object> mapper = tenantIdMapperRepresentation(alias, tenantId);
        Optional<Map<String, Object>> existing =
                keycloak.listIdentityProviderMappers(alias).stream()
                        .filter(m -> TENANT_ID_MAPPER_NAME.equals(m.get("name")))
                        .findFirst();
        if (existing.isPresent() && existing.get().get("id") != null) {
            String id = String.valueOf(existing.get().get("id"));
            mapper.put("id", id);
            keycloak.updateIdentityProviderMapper(alias, id, mapper);
        } else {
            keycloak.createIdentityProviderMapper(alias, mapper);
        }
    }

    static Map<String, Object> tenantIdMapperRepresentation(String alias, String tenantId) {
        Map<String, Object> mapper = new HashMap<>();
        mapper.put("name", TENANT_ID_MAPPER_NAME);
        mapper.put("identityProviderAlias", alias);
        mapper.put("identityProviderMapper", "hardcoded-attribute-idp-mapper");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("syncMode", "FORCE");
        cfg.put("attribute", "tenant_id");
        cfg.put("attribute.value", tenantId);
        mapper.put("config", cfg);
        return mapper;
    }

    private void fillOidcConfig(
            TenantSsoConfig c, Map<String, Object> kcConfig, String plainSecret) {
        if (c.getOidcDiscoveryUrl() != null) {
            // Keycloak resolves metadata itself when given the well-known
            // document - one field rather than four endpoint URLs the tenant
            // would have to copy individually.
            kcConfig.put("discoveryEndpoint", c.getOidcDiscoveryUrl());
            kcConfig.put("useDiscoveryEndpoint", "true");
        } else {
            kcConfig.put("authorizationUrl", c.getOidcAuthorizationUrl());
            kcConfig.put("tokenUrl", c.getOidcTokenUrl());
            if (c.getOidcUserInfoUrl() != null) {
                kcConfig.put("userInfoUrl", c.getOidcUserInfoUrl());
            }
            if (c.getOidcJwksUrl() != null) {
                kcConfig.put("jwksUrl", c.getOidcJwksUrl());
            }
            if (c.getOidcIssuer() != null) {
                kcConfig.put("issuer", c.getOidcIssuer());
                kcConfig.put("validateSignature", "true");
            }
        }
        kcConfig.put("clientId", c.getOidcClientId());
        String secret =
                plainSecret != null && !plainSecret.isBlank()
                        ? plainSecret
                        : (c.getClientSecretEncrypted() != null
                                ? encryptionService.decrypt(c.getClientSecretEncrypted())
                                : null);
        if (secret != null) {
            kcConfig.put("clientSecret", secret);
        }
        kcConfig.putIfAbsent("defaultScope", "openid profile email");
        kcConfig.put("syncMode", "IMPORT");
    }

    private void fillSamlConfig(TenantSsoConfig c, Map<String, Object> kcConfig) {
        kcConfig.put("singleSignOnServiceUrl", c.getSamlSsoUrl());
        kcConfig.put("nameIDPolicyFormat", c.getSamlNameIdFormat());
        if (c.getSamlEntityId() != null) {
            kcConfig.put("idpEntityId", c.getSamlEntityId());
        }
        if (c.getSamlX509Cert() != null) {
            kcConfig.put("signingCertificate", stripPem(c.getSamlX509Cert()));
            kcConfig.put("validateSignature", "true");
            kcConfig.put("wantAuthnRequestsSigned", "false");
        }
        kcConfig.put("syncMode", "IMPORT");
        kcConfig.put("postBindingResponse", "true");
        kcConfig.put("postBindingAuthnRequest", "true");
    }

    private void validate(String tenantSchema, TenantSsoUpsertRequest req) {
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (req.providerType() == TenantSsoConfig.ProviderType.OIDC) {
            boolean discovery = req.oidcDiscoveryUrl() != null && !req.oidcDiscoveryUrl().isBlank();
            if (discovery) {
                requireHttps(req.oidcDiscoveryUrl(), "oidcDiscoveryUrl");
                requirePublicHost(req.oidcDiscoveryUrl(), "oidcDiscoveryUrl");
            } else {
                requireHttps(req.oidcAuthorizationUrl(), "oidcAuthorizationUrl");
                requireHttps(req.oidcTokenUrl(), "oidcTokenUrl");
                // SEC-019: Keycloak fetches these server-side - no internal targets (SSRF).
                requirePublicHost(req.oidcTokenUrl(), "oidcTokenUrl");
                if (req.oidcUserInfoUrl() != null && !req.oidcUserInfoUrl().isBlank()) {
                    requireHttps(req.oidcUserInfoUrl(), "oidcUserInfoUrl");
                    requirePublicHost(req.oidcUserInfoUrl(), "oidcUserInfoUrl");
                }
                if (req.oidcJwksUrl() != null && !req.oidcJwksUrl().isBlank()) {
                    requireHttps(req.oidcJwksUrl(), "oidcJwksUrl");
                    requirePublicHost(req.oidcJwksUrl(), "oidcJwksUrl");
                }
            }
            if (req.oidcClientId() == null || req.oidcClientId().isBlank()) {
                throw new IllegalArgumentException("oidcClientId is required for OIDC");
            }
        } else {
            requireHttps(req.samlSsoUrl(), "samlSsoUrl");
            if (req.samlX509Cert() == null || req.samlX509Cert().isBlank()) {
                // Without the IdP's signing cert Keycloak can't verify
                // assertions - accept the config but flag it so the UI can
                // warn, rather than silently storing a broken SAML IdP.
                throw new IllegalArgumentException(
                        "samlX509Cert is required for SAML - Keycloak must be able to verify"
                                + " assertion signatures");
            }
        }
    }

    private static void requireHttps(String url, String field) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException(field + " must be an https:// URL");
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("https")) {
                throw e;
            }
            throw new IllegalArgumentException(field + " is not a valid URL");
        }
    }

    /**
     * SEC-019: resolve the host and refuse it if ANY address is loopback, site-local, link-local,
     * any-local, multicast, or in {@link ReservedAddressRanges} (CGNAT, cloud metadata, ULA, ...).
     * An unresolvable host is refused too. Residual: DNS can change between this check and
     * Keycloak's own fetch (rebinding); Keycloak's outbound traffic should also be egress-filtered.
     */
    private void requirePublicHost(String url, String field) {
        String host = URI.create(url.trim()).getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        InetAddress[] addresses;
        try {
            addresses = hostResolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(field + " host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException(field + " host could not be resolved");
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress()
                    || a.isSiteLocalAddress()
                    || a.isLinkLocalAddress()
                    || a.isAnyLocalAddress()
                    || a.isMulticastAddress()
                    || ReservedAddressRanges.contains(a)) {
                throw new IllegalArgumentException(
                        field + " must point at a public host, not an internal address");
            }
        }
    }

    /** PEM block -> bare base64, the form Keycloak's signingCertificate expects. */
    static String stripPem(String pem) {
        return pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record TenantSsoStatus(
            boolean configured,
            TenantSsoConfig.ProviderType providerType,
            String displayName,
            String alias,
            Boolean realmEnabled,
            boolean clientSecretSet,
            String brokerRedirectUri) {}
}
