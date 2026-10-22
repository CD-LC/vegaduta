package com.agenticai.core.agents.directory;

import com.agenticai.core.agents.a2a.A2aAgentCardService;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.trust.CapabilityManifestService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Publish/unpublish/read for the public agent directory (Know-Your-Agent discovery). Publish
 * captures <b>signed snapshots</b> - the A2A card and the capability manifest, both signed over the
 * operator key - into {@code control_plane.agent_directory_entry}, so public readers consume
 * exactly the artifact that was published and verify it against {@code /.well-known/jwks.json}
 * without any call into the tenant schema.
 *
 * <p>Snapshot-on-publish (rather than live re-read) is deliberate: the directory must not become a
 * confused-deputy path where a tenant-edited agent silently rewrites what external parties
 * verified.
 */
@ConditionalOnEditionFeature(EditionFeature.A2A_DIRECTORY)
@Service
public class AgentDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentDirectoryService.class);
    static final int MAX_LIST_LIMIT = 100;

    private final AgentDirectoryRepository repository;
    private final A2aAgentCardService cardService;
    private final CapabilityManifestService manifestService;
    private final ObjectProvider<AuditLogService> auditLogService;
    private final ObjectMapper objectMapper = JsonMappers.plain();

    public AgentDirectoryService(
            AgentDirectoryRepository repository,
            A2aAgentCardService cardService,
            CapabilityManifestService manifestService,
            ObjectProvider<AuditLogService> auditLogService) {
        this.repository = repository;
        this.cardService = cardService;
        this.manifestService = manifestService;
        this.auditLogService = auditLogService;
    }

    /**
     * Publish (or re-publish) an agent. Runs inside the caller's tenant context so the
     * card/manifest builders see the right schema; the entry itself lands in {@code control_plane}.
     */
    @Transactional
    public AgentDirectoryEntry publish(
            Agent agent,
            String displayName,
            String description,
            List<String> capabilities,
            String homepageUrl,
            String publicBaseUrl) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (!StringUtils.hasText(tenant)) {
            throw new IllegalStateException("directory publish needs a tenant context");
        }
        Map<String, Object> card = cardService.agentCard(agent, publicBaseUrl);
        Map<String, Object> manifest = manifestService.manifest(agent, publicBaseUrl);

        AgentDirectoryEntry entry =
                repository.findById(agent.getId()).orElseGet(AgentDirectoryEntry::new);
        entry.setAgentId(agent.getId());
        entry.setTenantId(tenant);
        entry.setDisplayName(
                StringUtils.hasText(displayName) ? displayName.trim() : agent.getName());
        entry.setDescription(description);
        entry.setHomepageUrl(homepageUrl);
        try {
            entry.setCapabilities(
                    capabilities == null ? null : objectMapper.writeValueAsString(capabilities));
            entry.setCardSnapshot(objectMapper.writeValueAsString(card));
            entry.setManifestSnapshot(objectMapper.writeValueAsString(manifest));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("directory snapshot serialization failed", e);
        }
        if (entry.getStatus() != AgentDirectoryEntry.Status.PUBLISHED
                || entry.getPublishedAt() == null) {
            entry.setPublishedAt(Instant.now());
        }
        entry.setStatus(AgentDirectoryEntry.Status.PUBLISHED);
        entry.touch(Instant.now());
        AgentDirectoryEntry saved = repository.save(entry);
        audit(
                "directory.agent.published",
                agent.getId().toString(),
                AuditOutcome.APPROVED,
                "tenant=" + tenant);
        return saved;
    }

    @Transactional
    public boolean unpublish(UUID agentId) {
        return repository
                .findById(agentId)
                .map(
                        entry -> {
                            entry.setStatus(AgentDirectoryEntry.Status.UNPUBLISHED);
                            entry.touch(Instant.now());
                            audit(
                                    "directory.agent.unpublished",
                                    agentId.toString(),
                                    AuditOutcome.APPROVED,
                                    null);
                            return true;
                        })
                .orElse(false);
    }

    /** Public listing - published entries only. Runs without a TenantContext. */
    @Transactional(readOnly = true)
    public List<AgentDirectoryEntry> listPublished(String query, int limit) {
        return listPublished(query, limit, 0L);
    }

    /** Offset variant; newest first with the agent id as tiebreaker so pages never overlap. */
    @Transactional(readOnly = true)
    public List<AgentDirectoryEntry> listPublished(String query, int limit, long offset) {
        int capped = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        long skip = Math.min(Math.max(offset, 0L), 1_000_000_000L);
        return repository.listPublished(pattern(query), new OffsetLimitRequest(skip, capped));
    }

    /** Published entries matching {@code query}, ignoring paging. */
    @Transactional(readOnly = true)
    public long countPublished(String query) {
        return repository.countPublished(pattern(query));
    }

    private static String pattern(String query) {
        return StringUtils.hasText(query) ? "%" + query.trim().toLowerCase() + "%" : null;
    }

    @Transactional(readOnly = true)
    public Optional<AgentDirectoryEntry> findPublished(UUID agentId) {
        return repository
                .findById(agentId)
                .filter(e -> e.getStatus() == AgentDirectoryEntry.Status.PUBLISHED);
    }

    /** The owning tenant's view of its own entries (any status). */
    @Transactional(readOnly = true)
    public List<AgentDirectoryEntry> listForTenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .map(repository::findByTenantIdOrderByUpdatedAtDesc)
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public Optional<AgentDirectoryEntry> findAny(UUID agentId) {
        return repository.findById(agentId);
    }

    private void audit(String action, String targetId, AuditOutcome outcome, String detail) {
        try {
            AuditLogService audit = auditLogService.getIfAvailable();
            if (audit != null) {
                String actor =
                        TenantContext.current().map(TenantContext::getSubject).orElse("system");
                audit.record(actor, action, "agent_directory_entry", targetId, outcome, detail);
            }
        } catch (RuntimeException e) {
            log.warn("Audit write failed for {}: {}", action, e.toString());
        }
    }
}
