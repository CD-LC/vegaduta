package com.agenticai.core.tenancy.apikey;

import com.agenticai.core.admin.permission.Permission;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.PermissionAuthoritySource;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.apikey.ApiKeyAuthenticationToken;
import com.agenticai.core.security.apikey.TenantApiKeyAuthenticator;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreateRequest;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyCreatedResponse;
import com.agenticai.core.tenancy.apikey.dto.TenantApiKeyResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns the create/rotate/enable/revoke/list lifecycle for tenant REST API
 * keys ({@code vdak_...}), plus the {@link TenantApiKeyAuthenticator} SPI
 * that {@code ApiKeyAuthFilter} calls on every key-authenticated request.
 *
 * <p><b>Scope rule:</b> a key's permission set is validated as a SUBSET of
 * the issuer's own effective permissions ({@link PermissionAuthoritySource})
 * - an admin holding only {@code MANAGE_BILLING} cannot mint an
 * all-powerful automation key. Same fail-closed posture as
 * {@code McpServerAccessGrantService}: when the permission store is absent,
 * only an empty scope set is issuable.
 *
 * <p><b>Inbound resolution mirrors {@code McpApiKeyAuthFilter}'s two steps</b>,
 * folded inside {@link #authenticate}: resolve the tenant schema from the
 * control-plane link with NO context, then set {@code TenantContext} to that
 * schema and verify the row (hash match + enabled + unexpired) from inside
 * the tenant's own schema - a row disabled the instant its link still
 * resolves is still rejected.
 */
@Service
public class TenantApiKeyService implements TenantApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(TenantApiKeyService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final int MAX_PERMISSIONS = 32;

    private final TenantApiKeyRepository repository;
    private final TenantResolutionService tenantResolutionService;
    private final AuditLogService auditLogService;
    private final TenantLimits tenantLimits;
    private final ObjectProvider<PermissionAuthoritySource> permissionSource;

    public TenantApiKeyService(
        TenantApiKeyRepository repository,
        TenantResolutionService tenantResolutionService,
        AuditLogService auditLogService,
        TenantLimits tenantLimits,
        ObjectProvider<PermissionAuthoritySource> permissionSource
    ) {
        this.repository = repository;
        this.tenantResolutionService = tenantResolutionService;
        this.auditLogService = auditLogService;
        this.tenantLimits = tenantLimits;
        this.permissionSource = permissionSource;
    }

    private String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    @Transactional
    public TenantApiKeyCreatedResponse create(TenantApiKeyCreateRequest request) {
        requireDeveloperApiAllowed();
        TenantContext context = TenantContext.current()
            .orElseThrow(() -> new IllegalStateException("API keys can only be issued from an authenticated tenant request."));
        Set<String> permissions = validatePermissions(request.permissions(), context);
        if (request.expiresAt() != null && !request.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt must be in the future (or omitted for no expiry).");
        }
        String rawKey = TenantApiKeyHasher.generateRawKey();
        String hash = TenantApiKeyHasher.sha256Hex(rawKey);
        TenantApiKey entity = repository.save(new TenantApiKey(
            request.name().strip(), hash, TenantApiKeyHasher.displayPrefix(rawKey),
            writeJson(permissions), context.getSubject(), request.expiresAt()));
        tenantResolutionService.link(ExternalAccountProvider.TENANT_API_KEY, hash, context.getTenantId());
        auditLogService.record(context.getSubject(), "tenancy.apikey.created", "TenantApiKey",
            entity.getId().toString(), AuditOutcome.SUCCESS,
            "Created API key '" + entity.getName() + "' with permissions " + permissions
                + (entity.getExpiresAt() != null ? " expiring " + entity.getExpiresAt() : ""));
        return new TenantApiKeyCreatedResponse(toResponse(entity), rawKey);
    }

    @Transactional
    public TenantApiKeyCreatedResponse rotate(UUID id) {
        TenantApiKey entity = repository.findById(id)
            .orElseThrow(() -> new TenantApiKeyNotFoundException(id));
        String oldHash = entity.getApiKeyHash();
        String rawKey = TenantApiKeyHasher.generateRawKey();
        String hash = TenantApiKeyHasher.sha256Hex(rawKey);
        entity.rotateKey(hash, TenantApiKeyHasher.displayPrefix(rawKey));
        entity = repository.save(entity);
        // Old key stops resolving immediately - repoint the control-plane
        // link, drop the old one (McpServerAccessGrantService precedent).
        linkToCurrentTenant(hash);
        tenantResolutionService.unlink(ExternalAccountProvider.TENANT_API_KEY, oldHash);
        auditLogService.record(currentActor(), "tenancy.apikey.rotated", "TenantApiKey",
            entity.getId().toString(), AuditOutcome.SUCCESS,
            "Rotated API key '" + entity.getName() + "'");
        return new TenantApiKeyCreatedResponse(toResponse(entity), rawKey);
    }

    @Transactional
    public TenantApiKeyResponse setEnabled(UUID id, boolean enabled) {
        TenantApiKey entity = repository.findById(id)
            .orElseThrow(() -> new TenantApiKeyNotFoundException(id));
        entity.setEnabled(enabled);
        return toResponse(repository.save(entity));
    }

    /**
     * Hard delete - the full key state is captured in the audit row first, so
     * "what could this key do, and when was it last used" survives the revoke
     * (the data-governance fix McpServerAccessGrantService#revoke documents).
     */
    @Transactional
    public void revoke(UUID id) {
        TenantApiKey entity = repository.findById(id)
            .orElseThrow(() -> new TenantApiKeyNotFoundException(id));
        auditLogService.record(currentActor(), "tenancy.apikey.revoked", "TenantApiKey",
            entity.getId().toString(), AuditOutcome.SUCCESS,
            "Revoked API key '" + entity.getName() + "' (prefix " + entity.getApiKeyDisplayPrefix()
                + "); permissions were " + entity.getPermissionsJson()
                + "; last used at " + entity.getLastUsedAt() + "; created at " + entity.getCreatedAt());
        tenantResolutionService.unlink(ExternalAccountProvider.TENANT_API_KEY, entity.getApiKeyHash());
        repository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<TenantApiKeyResponse> listAll() {
        Instant now = Instant.now();
        return repository.findAllByOrderByCreatedAtDesc().stream()
            .map(e -> toResponse(e, now)).toList();
    }

    /**
     * SPI for {@code ApiKeyAuthFilter}: {@code rawKey} still carries the
     * {@code vdak_} prefix. Owns its own TenantContext internally - this is
     * called BEFORE TenantContextFilter runs, so the context set here is for
     * the tenant-schema lookup only and is cleared in {@code finally}.
     */
    @Override
    public Optional<ApiKeyAuthenticationToken> authenticate(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(TenantApiKeyHasher.KEY_PREFIX)) {
            return Optional.empty();
        }
        Optional<String> tenantSchema = tenantResolutionService.resolveTenantSchema(
            ExternalAccountProvider.TENANT_API_KEY, TenantApiKeyHasher.sha256Hex(rawKey));
        if (tenantSchema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(TenantContext.of(tenantSchema.get(),
            "apikey:resolve", Set.of()));
        try {
            Optional<TenantApiKey> found = repository.findByApiKeyHash(TenantApiKeyHasher.sha256Hex(rawKey))
                .filter(TenantApiKey::isEnabled)
                .filter(k -> !k.isExpired(Instant.now()));
            if (found.isEmpty()) {
                return Optional.empty();
            }
            TenantApiKey key = found.get();
            key.recordUsage();
            repository.save(key);
            return Optional.of(new ApiKeyAuthenticationToken(
                tenantSchema.get(), key.getId(), key.getName(), readPermissions(key)));
        } finally {
            // ApiKeyAuthFilter ran before TenantContextFilter; the context set
            // for this lookup must not leak into the request proper - the
            // filter sets the real one from the token next.
            TenantContext.clear();
        }
    }

    /**
     * Every requested permission must be a real catalog name AND one the
     * issuer holds (GitHub-PAT-style: a key never exceeds its issuer).
     * {@code Permission.valueOf} answers the first; the SPI answers the
     * second. Fail-closed when the permission store is absent.
     */
    private Set<String> validatePermissions(Set<String> requested, TenantContext issuer) {
        Set<String> cleaned = requested == null ? Set.of() : requested.stream()
            .filter(StringUtils::hasText)
            .map(p -> p.strip().toUpperCase(java.util.Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (cleaned.size() > MAX_PERMISSIONS) {
            throw new IllegalArgumentException("At most " + MAX_PERMISSIONS + " permissions per key.");
        }
        for (String p : cleaned) {
            try {
                Permission.valueOf(p);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown permission '" + p + "' - see the permission catalog.");
            }
        }
        if (cleaned.isEmpty()) {
            return cleaned;
        }
        PermissionAuthoritySource source = permissionSource.getIfAvailable();
        if (source == null) {
            throw new IllegalStateException(
                "Cannot validate API key permissions - the permission store is unavailable.");
        }
        Set<String> roleNames = issuer.getRoles().stream()
            .filter(r -> r.startsWith("ROLE_"))
            .map(r -> r.substring("ROLE_".length()))
            .collect(Collectors.toUnmodifiableSet());
        Set<String> issuerPermissions = source.effectivePermissions(
            issuer.getTenantId(), issuer.getSubject(), roleNames);
        Set<String> exceeded = cleaned.stream()
            .filter(p -> !issuerPermissions.contains(p))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!exceeded.isEmpty()) {
            throw new IllegalArgumentException(
                "A key cannot carry permissions its issuer does not hold: " + exceeded);
        }
        return cleaned;
    }

    private void requireDeveloperApiAllowed() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.developerApiAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include the developer API - upgrade your plan to issue REST API keys.");
        }
    }

    private void linkToCurrentTenant(String hash) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException(
                "Cannot rotate an API key with no TenantContext - must be an authenticated tenant request."));
        tenantResolutionService.link(ExternalAccountProvider.TENANT_API_KEY, hash, tenantId);
    }

    private TenantApiKeyResponse toResponse(TenantApiKey entity) {
        return toResponse(entity, Instant.now());
    }

    private TenantApiKeyResponse toResponse(TenantApiKey entity, Instant now) {
        return new TenantApiKeyResponse(
            entity.getId(), entity.getName(), entity.getApiKeyDisplayPrefix(),
            readPermissions(entity), entity.getCreatedBy(), entity.getExpiresAt(),
            entity.getLastUsedAt(), entity.isEnabled(), entity.isExpired(now),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private Set<String> readPermissions(TenantApiKey entity) {
        if (!StringUtils.hasText(entity.getPermissionsJson())) {
            return Set.of();
        }
        try {
            return MAPPER.readValue(entity.getPermissionsJson(), new TypeReference<Set<String>>() { });
        } catch (Exception e) {
            log.error("Failed to parse stored API key permissions for {} - treating as empty", entity.getId(), e);
            return Set.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize API key permissions", e);
        }
    }
}
