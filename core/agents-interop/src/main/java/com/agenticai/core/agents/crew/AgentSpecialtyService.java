package com.agenticai.core.agents.crew;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caller-facing management of an agent's specialist roles (the {@link SpecialistRegistry} rows the
 * {@code delegate_to_specialist} tool discovers). Access is enforced through {@link
 * AgentService#requireAccess} - the agent must live in the caller's tenant schema (tenant routing
 * is schema-per-tenant, so an id from another tenant simply does not resolve) and the caller needs
 * VIEWER to read / EDITOR to change. No tenant id is ever accepted from a request body.
 */
@Service
public class AgentSpecialtyService {

    public static final int MAX_SPECIALTIES_PER_AGENT = 20;
    public static final int MAX_TAG_LENGTH = 50;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /** Lower-case letters/digits, with single inner '-', '_' or '.' separators (e.g. "billing", "tax-advice"). */
    private static final Pattern TAG_FORMAT = Pattern.compile("^[a-z0-9]+(?:[-_.][a-z0-9]+)*$");

    /** One requested specialty (input to {@link #replaceForAgent}). */
    public record Specialty(String capabilityTag, String description) {}

    private final AgentService agentService;
    private final SpecialistRegistry registry;

    public AgentSpecialtyService(AgentService agentService, SpecialistRegistry registry) {
        this.agentService = agentService;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public List<AgentSpecialization> list(UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return registry.findByAgent(agentId).stream()
            .sorted((a, b) -> a.getCapabilityTag().compareTo(b.getCapabilityTag()))
            .toList();
    }

    /** Distinct capability tags in the caller's tenant - the autocomplete roster. Any authenticated tenant member may read it. */
    @Transactional(readOnly = true)
    public List<String> listTenantTags() {
        return registry.listDistinctCapabilityTags();
    }

    /** Replaces the agent's whole set of specialties with {@code requested}. Idempotent. Throws IllegalArgumentException (HTTP 400) on invalid input. */
    @Transactional
    public List<AgentSpecialization> replaceForAgent(UUID agentId, List<Specialty> requested) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        Map<String, String> desired = validate(requested);

        Set<String> desiredTags = desired.keySet();
        for (AgentSpecialization existing : registry.findByAgent(agentId)) {
            if (!desiredTags.contains(existing.getCapabilityTag())) {
                registry.deregister(agentId, existing.getCapabilityTag());
            }
        }
        List<AgentSpecialization> result = new ArrayList<>();
        for (Map.Entry<String, String> e : desired.entrySet()) {
            result.add(registry.register(agentId, e.getKey(), e.getValue()));
        }
        result.sort((a, b) -> a.getCapabilityTag().compareTo(b.getCapabilityTag()));
        return result;
    }

    /** Normalizes + validates; returns tag -> description in request order. Package-private for tests. */
    static Map<String, String> validate(List<Specialty> requested) {
        if (requested == null) {
            throw new IllegalArgumentException("specialties is required");
        }
        if (requested.size() > MAX_SPECIALTIES_PER_AGENT) {
            throw new IllegalArgumentException("An agent can have at most " + MAX_SPECIALTIES_PER_AGENT + " specialties");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Specialty s : requested) {
            if (s == null) {
                throw new IllegalArgumentException("specialty entries must not be null");
            }
            String tag = SpecialistRegistry.normalize(s.capabilityTag());
            if (tag.isEmpty()) {
                throw new IllegalArgumentException("capabilityTag is required");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException("capabilityTag must be at most " + MAX_TAG_LENGTH + " characters");
            }
            if (!TAG_FORMAT.matcher(tag).matches()) {
                throw new IllegalArgumentException(
                    "capabilityTag may only contain letters, digits and single '-', '_' or '.' separators");
            }
            String description = s.description() == null ? null : s.description().trim();
            if (description != null && description.isEmpty()) {
                description = null;
            }
            if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
            }
            out.put(tag, description);
        }
        return out;
    }
}
