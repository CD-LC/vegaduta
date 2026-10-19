package com.agenticai.core.agents.crew;

import com.agenticai.core.security.HotpathLookups;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The SpecialistRegistry: marks an {@code Agent} as a callable specialist for other agents, by a
 * role/capability tag, tenant-scoped through the same schema-per-tenant routing every other
 * agent-owned table in this module relies on (see migration V304's header).
 *
 * <p>Plain JPA-repository-backed service, direct field (not {@code ObjectProvider}) on its callers
 * where the caller is itself same-module - this class has no cross-module optional dependency of
 * its own, unlike e.g. {@code PlatformIssueService}.
 */
@Service
public class SpecialistRegistry {

    private final AgentSpecializationRepository repository;

    public SpecialistRegistry(AgentSpecializationRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers (or, if this agent already carries this tag, updates the description of) a
     * specialist capability. Idempotent by design - re-registering the same (agent, tag) pair is an
     * update, never a duplicate row (see the migration's unique constraint).
     */
    public AgentSpecialization register(UUID agentId, String capabilityTag, String description) {
        String normalized = normalize(capabilityTag);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("capabilityTag is required");
        }
        return repository
                .findByAgentIdAndCapabilityTag(agentId, normalized)
                .map(
                        existing -> {
                            existing.setDescription(description);
                            return repository.save(existing);
                        })
                .orElseGet(
                        () ->
                                repository.save(
                                        new AgentSpecialization(agentId, normalized, description)));
    }

    /** Removes one (agent, tag) registration. A no-op if it was never registered. */
    public void deregister(UUID agentId, String capabilityTag) {
        repository.deleteByAgentIdAndCapabilityTag(agentId, normalize(capabilityTag));
    }

    /**
     * Every specialist registered for {@code capabilityTag} (case/whitespace-insensitive),
     * oldest-registered first.
     */
    public List<AgentSpecialization> findByCapability(String capabilityTag) {
        return repository.findByCapabilityTagOrderByCreatedAtAsc(normalize(capabilityTag));
    }

    /** Every capability {@code agentId} is registered for. */
    public List<AgentSpecialization> findByAgent(UUID agentId) {
        return repository.findByAgentId(agentId);
    }

    /**
     * Every distinct capability tag registered in this tenant - the roster {@code
     * delegate_to_specialist}'s tool description lists.
     */
    public List<String> listDistinctCapabilityTags() {
        return HotpathLookups.tenant(
                "crew.capability-tags", () -> List.copyOf(repository.findDistinctCapabilityTags()));
    }

    /**
     * Trimmed + lower-cased so "Billing", " billing", "billing " all address the same
     * specialist(s). Never null (blank for null input).
     */
    static String normalize(String capabilityTag) {
        return capabilityTag == null ? "" : capabilityTag.trim().toLowerCase(Locale.ROOT);
    }
}
