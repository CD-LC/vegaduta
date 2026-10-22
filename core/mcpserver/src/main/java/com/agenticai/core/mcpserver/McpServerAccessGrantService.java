package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreateRequest;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantCreatedResponse;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantResponse;
import com.agenticai.core.mcpserver.dto.McpServerAccessGrantUpdateRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns the create/rotate/revoke/list lifecycle for inbound MCP API keys, and the two-step "raw key
 * -> tenant schema -> grant row" resolution {@link McpApiKeyAuthFilter} needs on every inbound MCP
 * request - split into two separate methods ({@link #resolveTenantSchema} then {@link
 * #findByRawKeyWithinCurrentTenant}) because the first must run BEFORE {@code TenantContext} is set
 * (control_plane is schema-less) and the second only works AFTER it is, exactly mirroring {@code
 * WorkflowWebhookController}'s two-step lookup.
 */
@Service
public class McpServerAccessGrantService {

    private static final Logger log = LoggerFactory.getLogger(McpServerAccessGrantService.class);
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final McpServerAccessGrantRepository repository;
    private final AgentService agentService;
    private final TenantResolutionService tenantResolutionService;
    private final AuditLogService auditLogService;
    private final TenantLimits tenantLimits;

    public McpServerAccessGrantService(
            McpServerAccessGrantRepository repository,
            AgentService agentService,
            TenantResolutionService tenantResolutionService,
            AuditLogService auditLogService,
            TenantLimits tenantLimits) {
        this.repository = repository;
        this.agentService = agentService;
        this.tenantResolutionService = tenantResolutionService;
        this.auditLogService = auditLogService;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Actor for a tenant-admin-initiated grant lifecycle change - null (honest "unknown", per
     * {@code AuditLogEntry}'s own javadoc) rather than a misleading placeholder if no TenantContext
     * subject is set for some reason.
     */
    private String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    @Transactional
    public McpServerAccessGrantCreatedResponse create(McpServerAccessGrantCreateRequest request) {
        // Plan gate - issuing inbound MCP API keys (programmatic agent access
        // from external systems) is a paid-tier developer-surface entitlement
        // (plan_limits.developer_api_allowed), enforced at the service
        // boundary so a direct API call cannot bypass it.
        requireDeveloperApiAllowed();
        Set<UUID> agentIds = validateAgentIds(request.exposedAgentIds());
        String rawKey = McpApiKeyHasher.generateRawKey();
        String hash = McpApiKeyHasher.sha256Hex(rawKey);
        McpServerAccessGrant entity =
                new McpServerAccessGrant(
                        request.name(),
                        hash,
                        McpApiKeyHasher.displayPrefix(rawKey),
                        writeJson(agentIds),
                        writeJson(nullToEmpty(request.exposedWorkflowIds())),
                        writeJson(nullToEmpty(request.exposedKnowledgeCollectionIds())));
        entity = repository.save(entity);
        linkKeyToCurrentTenant(hash);
        auditLogService.record(
                currentActor(),
                "mcpserver.grant.created",
                "McpServerAccessGrant",
                entity.getId().toString(),
                AuditOutcome.SUCCESS,
                "Created MCP server access grant '"
                        + entity.getName()
                        + "' exposing agent ids "
                        + agentIds);
        return new McpServerAccessGrantCreatedResponse(toResponse(entity), rawKey);
    }

    @Transactional
    public McpServerAccessGrantCreatedResponse rotate(UUID id) {
        McpServerAccessGrant entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerAccessGrantNotFoundException(id));
        String oldHash = entity.getApiKeyHash();
        String rawKey = McpApiKeyHasher.generateRawKey();
        String hash = McpApiKeyHasher.sha256Hex(rawKey);
        entity.rotateKey(hash, McpApiKeyHasher.displayPrefix(rawKey));
        entity = repository.save(entity);
        // Old key must stop resolving immediately - repoint the control_plane
        // link to the new hash and drop the old one, same "repointed rather
        // than left stale" posture TenantResolutionService#link already
        // documents for the (rare) case of a reused external account id.
        linkKeyToCurrentTenant(hash);
        tenantResolutionService.unlink(ExternalAccountProvider.MCP_SERVER_KEY, oldHash);
        auditLogService.record(
                currentActor(),
                "mcpserver.grant.rotated",
                "McpServerAccessGrant",
                entity.getId().toString(),
                AuditOutcome.SUCCESS,
                "Rotated API key for MCP server access grant '" + entity.getName() + "'");
        return new McpServerAccessGrantCreatedResponse(toResponse(entity), rawKey);
    }

    @Transactional
    public McpServerAccessGrantResponse setEnabled(UUID id, boolean enabled) {
        McpServerAccessGrant entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerAccessGrantNotFoundException(id));
        entity.setEnabled(enabled);
        return toResponse(repository.save(entity));
    }

    @Transactional
    public McpServerAccessGrantResponse updateExposedIds(
            UUID id, McpServerAccessGrantUpdateRequest request) {
        McpServerAccessGrant entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerAccessGrantNotFoundException(id));
        Set<UUID> agentIds = validateAgentIds(request.exposedAgentIds());
        entity.updateExposedIds(
                writeJson(agentIds),
                writeJson(nullToEmpty(request.exposedWorkflowIds())),
                writeJson(nullToEmpty(request.exposedKnowledgeCollectionIds())));
        return toResponse(repository.save(entity));
    }

    @Transactional
    public void revoke(UUID id) {
        McpServerAccessGrant entity =
                repository
                        .findById(id)
                        .orElseThrow(() -> new McpServerAccessGrantNotFoundException(id));
        // Data-governance audit fix (2026-07-20): record the full grant state BEFORE the hard
        // delete below destroys it - name, exposed agent/workflow/knowledge ids, and lastUsedAt
        // are otherwise gone forever the moment a grant is revoked, with no way to answer "what
        // was this key allowed to do, and when was it last used" during a later incident
        // investigation. This audit_log_entry row is append-only and outlives the grant row.
        auditLogService.record(
                currentActor(),
                "mcpserver.grant.revoked",
                "McpServerAccessGrant",
                entity.getId().toString(),
                AuditOutcome.SUCCESS,
                "Revoked MCP server access grant '"
                        + entity.getName()
                        + "' (key prefix "
                        + entity.getApiKeyDisplayPrefix()
                        + "); exposed agent ids were "
                        + entity.getExposedAgentIds()
                        + "; last used at "
                        + entity.getLastUsedAt()
                        + "; created at "
                        + entity.getCreatedAt());
        tenantResolutionService.unlink(
                ExternalAccountProvider.MCP_SERVER_KEY, entity.getApiKeyHash());
        repository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<McpServerAccessGrantResponse> listAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    /**
     * Step 1 of inbound auth - control_plane lookup, callable with no {@code TenantContext} set at
     * all.
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveTenantSchema(String rawApiKey) {
        return tenantResolutionService.resolveTenantSchema(
                ExternalAccountProvider.MCP_SERVER_KEY, McpApiKeyHasher.sha256Hex(rawApiKey));
    }

    /**
     * Step 2 of inbound auth - {@code TenantContext} MUST already be set to the schema {@link
     * #resolveTenantSchema} returned.
     */
    @Transactional
    public Optional<McpServerAccessGrant> findByRawKeyWithinCurrentTenant(String rawApiKey) {
        Optional<McpServerAccessGrant> found =
                repository
                        .findByApiKeyHash(McpApiKeyHasher.sha256Hex(rawApiKey))
                        .filter(McpServerAccessGrant::isEnabled);
        found.ifPresent(
                entity -> {
                    entity.recordUsage();
                    repository.save(entity);
                });
        return found;
    }

    /**
     * OAuth path: the grant a validated token is linked to; {@code TenantContext} must already be
     * set. Empty if missing or disabled.
     */
    @Transactional
    public Optional<McpServerAccessGrant> findEnabledByIdWithinCurrentTenant(UUID grantId) {
        Optional<McpServerAccessGrant> found =
                repository.findById(grantId).filter(McpServerAccessGrant::isEnabled);
        found.ifPresent(
                entity -> {
                    entity.recordUsage();
                    repository.save(entity);
                });
        return found;
    }

    Set<UUID> parseExposedAgentIds(McpServerAccessGrant entity) {
        return readJsonUuidSet(entity.getExposedAgentIds());
    }

    /**
     * Same shape as {@link #parseExposedAgentIds} - used by {@code McpApiKeyAuthFilter} to populate
     * {@code McpCallerContext#exposedWorkflowIds()} so {@code McpCallWorkflowToolCallback}/ {@code
     * McpListMyWorkflowsToolCallback} can enforce/list this grant's exposed-workflow set, the first
     * tools to actually read {@code exposedWorkflowIds} (see McpServerAccessGrant's own javadoc for
     * the "persisted but not yet enforced" gap this closes).
     */
    Set<UUID> parseExposedWorkflowIds(McpServerAccessGrant entity) {
        return readJsonUuidSet(entity.getExposedWorkflowIds());
    }

    /**
     * Same shape as {@link #parseExposedAgentIds} - used by {@code McpApiKeyAuthFilter} to populate
     * {@code McpCallerContext#exposedKnowledgeCollectionIds()} so {@code
     * McpSearchKnowledgeToolCallback} can restrict a search to exactly this grant's
     * exposed-collection set - the first tool to actually read {@code
     * exposedKnowledgeCollectionIds} (see McpServerAccessGrant's own javadoc for the "persisted but
     * not yet enforced" gap this closes).
     */
    Set<UUID> parseExposedKnowledgeCollectionIds(McpServerAccessGrant entity) {
        return readJsonUuidSet(entity.getExposedKnowledgeCollectionIds());
    }

    private void requireDeveloperApiAllowed() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.developerApiAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include the developer API - upgrade your plan to issue MCP server access keys.");
        }
    }

    private void linkKeyToCurrentTenant(String hash) {
        String tenantId =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Cannot issue an MCP server API key with no TenantContext set - this must be called from an "
                                                        + "authenticated tenant-admin request."));
        tenantResolutionService.link(ExternalAccountProvider.MCP_SERVER_KEY, hash, tenantId);
    }

    private Set<UUID> validateAgentIds(Set<UUID> agentIds) {
        Set<UUID> ids = nullToEmpty(agentIds);
        // Fail fast, at grant-save time, rather than silently exposing a
        // dangling id a caller could never actually invoke - each id must be
        // a real agent belonging to THIS tenant (AgentService itself is
        // already tenant-scoped via TenantContext/Hibernate schema routing,
        // same as every other tenant-owned lookup in this codebase).
        for (UUID agentId : ids) {
            agentService.get(agentId);
        }
        return ids;
    }

    private McpServerAccessGrantResponse toResponse(McpServerAccessGrant entity) {
        return new McpServerAccessGrantResponse(
                entity.getId(),
                entity.getName(),
                entity.getApiKeyDisplayPrefix(),
                entity.isEnabled(),
                readJsonUuidSet(entity.getExposedAgentIds()),
                readJsonUuidSet(entity.getExposedWorkflowIds()),
                readJsonUuidSet(entity.getExposedKnowledgeCollectionIds()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastUsedAt());
    }

    private static Set<UUID> nullToEmpty(Set<UUID> ids) {
        return ids == null ? Collections.emptySet() : ids;
    }

    private Set<UUID> readJsonUuidSet(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptySet();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Set<UUID>>() {});
        } catch (Exception e) {
            log.error(
                    "Failed to parse stored MCP server access grant id list - treating as empty",
                    e);
            return Collections.emptySet();
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize MCP server access grant config", e);
        }
    }
}
