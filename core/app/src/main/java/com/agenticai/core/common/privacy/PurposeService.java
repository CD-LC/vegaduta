package com.agenticai.core.common.privacy;

import com.agenticai.core.agents.consent.PurposeAccessPolicy;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Purpose tags on knowledge collections and agents, and the rule that stops an agent reading or
 * writing a collection collected for another purpose.
 *
 * <p>Rule: a collection with no tag is unrestricted (existing behaviour, so nothing changes until
 * a tenant starts tagging). A tagged collection is usable only by an agent that carries at least
 * one of its purposes. An agent's purposes are its own tags plus the purpose of its enabled consent
 * policy, so a support agent that asked for consent "for support" can use "support" collections
 * without a second step.
 *
 * <p>Enforced for the knowledge tools an agent is given (search, list, add). NOT enforced for
 * workflow nodes, direct knowledge API calls, or agent/tenant memory; see
 * docs/compliance/dpdp-obligations.md.
 */
@Service
public class PurposeService implements PurposeAccessPolicy {

    public static final String KNOWLEDGE_COLLECTION = "KNOWLEDGE_COLLECTION";
    public static final String AGENT = "AGENT";

    private final PurposeJdbcRepository repository;
    private final ConsentPolicyService consent;

    public PurposeService(PurposeJdbcRepository repository, ConsentPolicyService consent) {
        this.repository = repository;
        this.consent = consent;
    }

    public List<String> purposes(String resourceType, UUID resourceId) {
        return repository.purposes(resourceType, resourceId);
    }

    /** Replaces the resource's tags with {@code purposes} (empty list = untagged). */
    @Transactional
    public List<String> replace(String resourceType, UUID resourceId, List<String> purposes, String actor) {
        Set<String> clean = new LinkedHashSet<>();
        for (String p : purposes == null ? List.<String>of() : purposes) {
            String purpose = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
            if (!ConsentPolicyService.PURPOSE.matcher(purpose).matches()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "purpose must be 1-64 characters of a-z, 0-9, '-' or '_'");
            }
            clean.add(purpose);
        }
        String table = KNOWLEDGE_COLLECTION.equals(resourceType) ? "knowledge_collection" : "agent";
        if (!repository.resourceExists(table, resourceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such resource");
        }
        repository.replaceTags(resourceType, resourceId, clean, actor);
        return List.copyOf(clean);
    }

    @Override
    public Set<UUID> permittedCollections(UUID agentId, Set<UUID> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) {
            return Set.of();
        }
        Set<String> agentPurposes = new HashSet<>(purposes(AGENT, agentId));
        consent.find(agentId).filter(ConsentPolicyService.Policy::enabled).ifPresent(p -> agentPurposes.add(p.purpose()));

        Set<UUID> permitted = new LinkedHashSet<>();
        for (UUID id : collectionIds) {
            List<String> tags = purposes(KNOWLEDGE_COLLECTION, id);
            if (tags.isEmpty() || tags.stream().anyMatch(agentPurposes::contains)) {
                permitted.add(id);
            }
        }
        return permitted;
    }
}
